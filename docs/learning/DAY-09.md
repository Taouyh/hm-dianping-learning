# DAY-09：优惠券秒杀优化代码复盘与面试训练

> 本文只按当前仓库 `D:\code\hm-dianping` 的实际代码整理。需要特别注意：当前代码已经切换到 Redis Stream，但消费线程中仍保留旧版阻塞队列注释，并且 Redisson 锁、事务代理的调用链没有完全接入当前消费主路径。下文将“已实现”“历史残留”“发现的问题”和“建议方向”分开说明。

## 一、业务流程与代码地图

### 1. 秒杀优化要解决什么问题

传统同步下单的大致流程是：HTTP 请求进入 Controller，查询秒杀券和库存，判断一人一单，生成订单号，扣减 MySQL 库存，插入订单，最后返回结果。这样所有慢操作都在请求线程中完成：数据库查询、库存更新、订单写入会占用连接和线程；并发高时，大量请求会同时访问 MySQL，容易形成数据库连接、锁和 CPU 压力，HTTP 响应也会变慢。

当前优化把“是否有资格抢”和“快速挡住无效请求”前移到 Redis Lua，把订单消息写入 Redis Stream；HTTP 线程在 Lua 成功后只返回订单 ID，不等待数据库订单落库。后台线程再从 Stream 取消息，执行数据库校验、扣减数据库库存和写订单。

这不是把 Redis 和 MySQL 变成一个跨系统事务，而是把高并发入口和较慢的数据库写入拆开：

- **Redis**：执行原子资格校验、Redis 库存扣减、Redis 一人一单标记、写入 Stream。
- **Java**：接收请求、生成订单 ID、调用 Lua、读取 Stream、组装消息、调用订单创建方法。
- **MySQL**：在订单创建方法中再次查询订单、用 `stock > 0` 条件扣减数据库库存、保存订单。

### 2. 优化前后流程对比

```text
传统同步：
HTTP -> Controller -> 查询 MySQL -> 查库存/查订单 -> 扣 MySQL 库存 -> 插入订单 -> 返回
                              （请求线程一直等待数据库）

当前代码的目标流程：
HTTP -> VoucherOrderController.seckillVoucher
     -> VoucherOrderServiceImpl.seckillVoucher
     -> Redis Lua：查 Redis 库存 + 查 Redis Set + 扣 Redis 库存
                   + 写入 stream.orders
     -> 返回订单 ID

后台线程：
@PostConstruct 启动的单线程消费者
     -> XREADGROUP 读取 stream.orders
     -> 解析 userId/voucherId/id
     -> 订单创建方法
     -> MySQL 查重 + 条件扣库存 + 保存 tb_voucher_order
     -> XACK（当前代码参数存在问题，见后文）
```

也可以用 Mermaid 表示：

```mermaid
flowchart TD
    A[POST /voucher-order/seckill/{id}] --> B[VoucherOrderController]
    B --> C[seckillVoucher(voucherId)]
    C --> D[RedisIdWorker 生成 orderId]
    D --> E[seckill.lua]
    E --> F{库存足够且未购买?}
    F -- 否 --> G[返回库存不足或重复下单]
    F -- 是 --> H[扣 Redis 库存]
    H --> I[SADD 用户购买标记]
    I --> J[XADD stream.orders]
    J --> K[返回 orderId]
    L[后台单线程 VoucherOrderHandler] --> M[XREADGROUP g1/c1]
    M --> N[组装 VoucherOrder]
    N --> O[createVoucherOrder]
    O --> P[MySQL 查重]
    P --> Q[stock = stock - 1 且 stock > 0]
    Q --> R[保存订单]
    R --> S[XACK]
```

### 3. 当前版本的真实调用链

1. `VoucherOrderController.seckillVoucher` 接收 `POST /voucher-order/seckill/{id}`，调用 `IVoucherOrderService.seckillVoucher`。
2. `VoucherOrderServiceImpl.seckillVoucher` 从 `UserHolder` 取当前用户，调用 `RedisIdWorker.nextId("order")` 预先生成订单 ID。
3. 通过 `StringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId, userId, orderId)` 执行 `src/main/resources/seckill.lua`。
4. Lua 成功后用 `XADD` 把 `userId`、`voucherId`、`id` 写入 `stream.orders`；Java 立即返回订单 ID。
5. `@PostConstruct` 的 `init` 启动一个 `newSingleThreadExecutor`，后台 `VoucherOrderHandler` 以消费者组 `g1`、消费者 `c1` 从 `stream.orders` 读取消息。
6. 当前**实际生效**的消费者代码是 `createVoucherOrder(voucherOrder)` 的直接调用，而不是下面定义的 `handleVoucherOrder(voucherOrder)`。
7. `createVoucherOrder` 查询 `tb_voucher_order` 中相同用户和优惠券的订单，使用 MyBatis-Plus 条件更新扣减 `tb_seckill_voucher.stock`，再保存订单。
8. 当前代码随后调用 `acknowledge("s1", "g1", record.getId())`。消息读写使用的 Stream 是 `stream.orders`，这里的 `s1` 与其不一致，属于需要修复或验证的明确疑点。

### 4. 关键文件对应关系

| 作用 | 当前文件/方法 | 真实职责 |
| --- | --- | --- |
| HTTP 入口 | `src/main/java/com/hmdp/controller/VoucherOrderController.java` 的 `seckillVoucher` | 接收优惠券 ID，转给 Service |
| 秒杀入口 | `VoucherOrderServiceImpl.seckillVoucher` | 取用户、生成 ID、执行 Lua、返回结果 |
| Lua 加载 | `VoucherOrderServiceImpl.SECKILL_SCRIPT` | 从 classpath 加载 `seckill.lua`，结果类型为 `Long` |
| Lua 脚本 | `src/main/resources/seckill.lua` | Redis 库存、一人一单、Stream 投递 |
| Redis 库存初始化 | `VoucherServiceImpl.addSeckillVoucher` | 保存秒杀券后写入 `seckill:stock:<voucherId>` |
| 消息消费 | `VoucherOrderServiceImpl.VoucherOrderHandler` | 消费者组读取 `stream.orders` |
| Pending 处理 | `VoucherOrderServiceImpl.handlePendingList` | 从 `stream.orders` 的 offset `0` 读取待处理消息并再次处理 |
| 订单持久化 | `VoucherOrderServiceImpl.createVoucherOrder` | MySQL 查重、条件扣库存、保存订单 |
| 数据实体 | `VoucherOrder` | 对应 `tb_voucher_order`，主键为输入的订单 ID |
| 数据访问 | `VoucherOrderMapper`、ServiceImpl | 查询和保存订单 |
| 分布式锁配置 | `RedissonConfig` | 连接本地 Redis `127.0.0.1:6379` |
| AOP 配置 | `HmDianPingApplication` | `@EnableAspectJAutoProxy(exposeProxy = true)` |

### 5. Redis、Java、MySQL 的职责边界

**Redis 侧**：

- `seckill:stock:<voucherId>` 是 Lua 读取和扣减的 Redis 库存。
- `seckill:order:<voucherId>` 是 Redis Set，member 是用户 ID，用于快速判断该用户是否已经抢过该券。
- `stream.orders` 是 Redis Stream，消息字段是 `userId`、`voucherId`、`id`。
- Lua 的多个 Redis 操作在一次脚本执行中完成，避免多个客户端命令交错。

**Java 侧**：

- 负责 HTTP 和 Redis 的连接；
- `RedisIdWorker` 生成订单号；
- 将 Stream 记录转换为 `VoucherOrder`；
- 运行后台消费线程；
- 代码中还定义了 Redisson 锁路径 `handleVoucherOrder`，但当前主消费路径没有调用它。

**MySQL 侧**：

- `tb_seckill_voucher` 保存数据库库存；
- `tb_voucher_order` 保存订单；
- 事务方法中先按 `user_id + voucher_id` 查重，再用 `stock > 0` 条件更新库存，最后 `save` 订单。
- `hmdp.sql` 对 `tb_voucher_order` 目前只看到 `id` 主键，没有看到 `(user_id, voucher_id)` 联合唯一索引，所以数据库不能单独替代一人一单控制。

### 6. 边界、异常与当前未实现能力

- Redis Lua 成功不等于 MySQL 订单一定成功。两个系统没有跨系统事务。
- 当前代码在数据库创建失败时没有看到 Redis 库存回补或 Redis 用户标记撤销逻辑。
- 当前 Stream 读取是消费者组模式，并且异常时会进入 `handlePendingList`；但没有看到最大重试次数、死信队列或人工补偿。
- `acknowledge` 使用了 `s1` 而不是读写用的 `stream.orders`，需要修正或通过集成环境确认。
- 当前消费者直接调用 `createVoucherOrder`，绕过了同类调用通常需要的 Spring 事务代理；因此 `@Transactional` 是否实际生效不能仅凭注解确认。
- `handleVoucherOrder` 中虽然定义了按用户加 Redisson 锁并通过 `proxy.createVoucherOrder` 的意图，但当前 `VoucherOrderHandler` 没有调用它；因此不能把 Redisson 锁说成当前生效链路已经提供的并发保护。
- 单实例单线程消费者、Redis 和 MySQL 可用性、消息积压、服务重启恢复等场景还需要实际环境验证。

## 二、Lua：原子秒杀入口的逐段理解

当前脚本按顺序做以下事情：

```lua
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local stockKey = "seckill:stock:"..voucherId
local orderKey = "seckill:order:"..voucherId
```

Java 没有传 `KEYS`，而是把三个值作为 `ARGV` 传入。脚本根据优惠券 ID 拼出库存 Key 和购买记录 Key。

```lua
if (tonumber(redis.call('get',stockKey))<= 0) then
    return 1
end
```

先读取 Redis 库存。库存小于等于 0 时返回 `1`，Java 将它转换为“库存不足”。脚本没有在这里查询 MySQL 库存。

```lua
if redis.call("sismember",orderKey,userId) == 1 then
    return 2
end
```

再用 Redis Set 判断当前用户是否已经在该优惠券的购买集合中。已存在返回 `2`，Java 将它转换为“不能重复下单”。

```lua
redis.call("incrby",stockKey,-1)
redis.call("sadd",orderKey,userId)
redis.call('xadd','stream.orders','*',
           'userId',userId,'voucherId',voucherId,'id',orderId)
return 0
```

通过校验后，依次扣减 Redis 库存、记录用户购买状态、向 `stream.orders` 追加订单消息，最后返回 `0`。Redis 脚本的原子性意味着脚本执行期间不会被另一个 Redis 客户端把这些判断和修改拆开插队：不会出现两个请求都看到同一个剩余库存后都扣减成功，也不会让同一用户的判断和写入被并发请求分离。

### 为什么不能只写 Java 查询再扣库存

如果 Java 先 `GET` 库存，判断大于 0，再单独 `DECR`，两个请求可能都在判断阶段读到库存 1，随后都继续扣减；“判断”和“修改”之间存在竞态。Lua 把读取、判断、扣减、标记和投递放到 Redis 一次脚本执行中，解决的是 Redis 内部这段高并发入口的原子性问题。

### Lua 原子性不覆盖 MySQL

脚本成功后，Redis 里已经扣库存、写入用户标记、写入 Stream；后台消费者才会尝试 MySQL。若 MySQL 连接失败、事务代理不生效、库存更新失败或进程异常，Redis 的成功状态不会自动回滚。当前代码没有实现一个跨 Redis/MySQL 的事务协调器，也没有看到失败后的库存回补。因此应该表达为“Redis 先完成资格和削峰，MySQL 异步落库，存在最终一致性边界”，而不是“Lua 保证整个下单事务成功”。

## 三、Redis Stream：当前生效方案与历史 BlockingQueue

### 1. 当前是 Redis Stream，不是 BlockingQueue

当前非注释代码调用：

```java
stringRedisTemplate.opsForStream().read(
    Consumer.from("g1", "c1"),
    StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
    StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
);
```

这对应 Redis 的 `XREADGROUP` 思路：Stream 名为 `stream.orders`，消费者组为 `g1`，消费者为 `c1`，每次最多读一条，最多阻塞两秒，按消费者组的 last-delivered 位置读取。

Lua 中的 `XADD stream.orders * ...` 不是 Java `BlockingQueue.add`，而是把一条带有 Stream ID 的记录追加到 Redis。消息字段为：

- `userId`：下单用户；
- `voucherId`：秒杀优惠券；
- `id`：预先生成的订单 ID。

`@PostConstruct` 启动的单线程消费者持续读取消息。成功处理后应对同一个 Stream、同一个消费者组执行 ACK，使消息从 Pending List 中确认完成。当前代码写成 `acknowledge("s1", "g1", ...)`，而读取的是 `stream.orders`，这是当前实现的可靠性疑点：应重点验证并通常应改为对 `stream.orders` ACK。

异常时 `handlePendingList` 使用 `ReadOffset.from("0")` 读取消费者组的待处理消息，并重新调用订单创建逻辑；异常循环只睡眠 20 毫秒，没有最大重试次数、死信或补偿记录。读取成功也不等于订单创建成功，只有数据库处理成功并且消息被正确 ACK，才算这条消息完成了当前消费者阶段。

### 2. 历史残留的 BlockingQueue

`VoucherOrderServiceImpl` 中保留了 `ArrayBlockingQueue`、`BlockingQueue` 的 import 和注释代码，`VoucherOrderServiceImplBefore.java` 也是未跟踪的历史文件。注释中的旧方案是 JVM 内部队列：Lua 成功后 Java 组装 `VoucherOrder`，调用 `orderTasks.add`；后台线程用 `take` 取出并处理。

它适合单实例学习和演示，但消息只在当前 JVM 内存中：进程重启可能丢失队列内容，多实例之间也不能共享；Redis Stream 可以由多个实例通过消费者组共享消息和消费进度。因此，本文不把 BlockingQueue 写成当前运行版本，只作为演进对比。

## 四、订单 ID、幂等与库存

### 1. 为什么提前生成订单 ID

`seckillVoucher` 在执行 Lua 前调用 `redisIdWorker.nextId("order")`。订单 ID 是业务生成的唯一标识，不依赖 MySQL 自增或插入结果，所以可以在异步数据库写入之前产生。它随后作为 Lua 的第三个参数，写入 Stream 的 `id` 字段，消费者再把这个字段映射为 `VoucherOrder.id`。

这样 HTTP 请求可以在消息投递成功后马上返回订单 ID，而不需要等待 MySQL 插入完成。

### 2. 订单 ID 不能单独保证幂等

同一条消息重复消费时，如果每次都重新生成 ID，主键也不能阻止重复业务订单；即使消息中的 ID 相同，主键只能阻止相同 ID 重复插入，不能自动证明“同一个用户不能购买同一张券”。当前代码的业务幂等判断是：

- Lua 使用 `SISMEMBER seckill:order:<voucherId> userId`；
- MySQL 创建方法再次按 `user_id` 和 `voucher_id` 查询；
- `tb_voucher_order` 当前 SQL 只看到 `id` 主键，没有联合唯一约束。

所以应把订单 ID、Redis Set、数据库查重和数据库唯一约束区分开来。当前项目没有看到联合唯一索引，建议后续评估增加 `(user_id, voucher_id)` 唯一约束，作为数据库最后一道保护，但这不是本次擅自修改的内容。

### 3. 两份库存的关系

创建秒杀券时，`VoucherServiceImpl.addSeckillVoucher` 先保存 MySQL 秒杀券，再把数量写入 `seckill:stock:<id>`。秒杀入口扣的是 Redis 库存；异步订单创建扣的是 MySQL 库存，使用：

```java
.setSql("stock = stock - 1")
.eq("voucher_id", voucherOrder.getVoucherId())
.gt("stock", 0)
.update();
```

`stock > 0` 是数据库侧的条件更新，可避免数据库库存被扣成负数；它不能让 Redis 和 MySQL 形成同一个事务。若 Redis 已扣减而数据库更新失败，当前代码没有自动回补逻辑。

## 五、Redisson 分布式锁与事务代理：必须按实际调用判断

### 1. 代码中确实定义了什么

`RedissonConfig` 创建了本地 Redis 的 `RedissonClient`。`VoucherOrderServiceImpl.handleVoucherOrder` 中按用户构造锁：

```java
RLock redisLock = redissonClient.getLock("lock:order:" + userId);
boolean isLock = redisLock.tryLock();
try {
    proxy.createVoucherOrder(voucherOrder);
} finally {
    redisLock.unlock();
}
```

设计意图是让同一用户的订单消息串行进入数据库创建方法，避免两个并发消费者同时通过数据库查重。锁和数据库事务职责不同：锁控制并发进入，事务保证一次数据库操作中的查重、扣库存、保存订单要么提交、要么回滚（前提是代理真正生效）。

### 2. 当前实际生效情况

当前 `VoucherOrderHandler.run` 在解析消息后直接执行：

```java
createVoucherOrder(voucherOrder);
```

没有调用 `handleVoucherOrder(voucherOrder)`，因此当前消费主路径没有实际获取 Redisson 锁，也没有执行其中的 `finally` 释放逻辑。`seckillVoucher` 中虽然把 `AopContext.currentProxy()` 赋给 `proxy`，但实际 Stream 路径并不使用这个字段。这个问题应如实向面试官说明：代码留下了锁和代理接入的意图，但当前提交版本的有效调用链尚未接通。

### 3. 事务代理问题

`createVoucherOrder` 标有 `@Transactional`。Spring 声明式事务通常由代理在方法进入和退出时开启、提交或回滚；同一个类里直接写 `this.createVoucherOrder(...)` 或直接调用 `createVoucherOrder(...)`，一般会绕过代理。当前后台线程正是同类直接调用，因此不能仅凭注解声称事务已经生效。

代码还配置了 `@EnableAspectJAutoProxy(exposeProxy = true)`，并在请求线程通过 `AopContext.currentProxy()` 获取代理。但代理对象保存在一个共享字段里，且赋值发生在秒杀请求中；如果后台线程先消费已有消息，或者多个请求交错，初始化和线程安全都需要验证。`handleVoucherOrder` 里使用代理调用是正确方向，但它本身当前未被主消费链调用。

最小修复方向应是：让消费者调用 `handleVoucherOrder`，并用稳定的 Spring 自代理注入或其他可靠方式获得代理；同时将 ACK 的 Stream 名称改为 `stream.orders`，再用集成测试验证异常、重复消息和事务回滚。本文不在没有运行 Redis/MySQL 集成环境的情况下大规模重写。

## 六、面试手写代码训练路线

训练目标不是背完整文件，而是能逐层写出主干，再解释每个失败边界。每一步写完先自检，再进入下一步。

### 第 1 步：传统同步秒杀

**先写：** Controller 调 Service；查秒杀券时间和库存；查询订单；条件扣库存；保存订单。

**记住：** `getById`、`query().eq(...).count()`、`setSql("stock = stock - 1")`、`.gt("stock", 0)`、`save`、`@Transactional`。

**易错：** 把查询库存和更新库存写成非条件更新；忘记用户 ID；把订单 ID交给数据库自增但实体是 `IdType.INPUT`。

**自检：** 是否说明请求线程等待数据库？是否考虑重复下单和超卖？事务覆盖哪些 SQL？

**追问：** 高并发下瓶颈在哪里？为什么还要异步化？

### 第 2 步：写 Lua 的资格校验

**先写骨架：** 读取 `ARGV[1..3]`，拼 `seckill:stock:` 和 `seckill:order:`；`GET` 库存；`SISMEMBER`；`INCRBY -1`；`SADD`；`XADD`；返回码。

**记住：** `redis.call`、`tonumber`、`SISMEMBER`、`INCRBY`、`SADD`、`XADD`。

**易错：** 先扣库存后判断重复；漏写用户标记；返回值和 Java 的 1/2/0 含义不一致；把 MySQL 操作写进 Lua。

**自检：** Redis 内部操作是否在一个脚本中？库存不足和重复购买是否可区分？消息是否包含三个字段？

**追问：** Lua 原子性是否覆盖 MySQL？为什么 Java 的 GET + DECR 不安全？

### 第 3 步：写 Java 秒杀入口

**先写：** 取 `UserHolder.getUser().getId()`；`redisIdWorker.nextId("order")`；`stringRedisTemplate.execute`；根据结果返回失败或订单 ID。

**记住：** `DefaultRedisScript<Long>`、`Collections.emptyList()`、`result.intValue()`。

**易错：** 把 `voucherId/userId/orderId` 传错顺序；忘记处理 `result == 1/2`；在请求线程里又查询 MySQL，破坏削峰目标。

**自检：** Lua 成功后是否立即返回？订单 ID 是否传入消息而不是在消费者重新生成？

**追问：** 为什么订单 ID 可以提前生成？消息重复时仅靠 ID 够不够？

### 第 4 步：写 Redis Stream 投递与消费

**先写：** Lua `XADD stream.orders * userId ...`；消费者组 `g1`、消费者 `c1`；`read(... lastConsumed())`；解析 Record；成功后对同一 Stream ACK。

**记住：** `opsForStream().read`、`Consumer.from`、`StreamReadOptions.count/block`、`StreamOffset.create`、`acknowledge`。

**易错：** 把 Stream 当成 Java 队列；读 `stream.orders` 却 ACK `s1`；只处理新消息不处理 Pending；把读到消息当成数据库成功。

**自检：** 能否说清 Stream、消费者组、Pending List、ACK？能否说明当前代码的重试边界？

**追问：** 消费者崩溃后消息在哪里？如何避免无限重试？

### 第 5 步：写用户维度 Redisson 锁

**先写：** `getLock("lock:order:" + userId)`；`tryLock`；成功后进入订单方法；`finally` 释放。

**记住：** `RLock`、`tryLock`、`unlock`、`isHeldByCurrentThread`。

**易错：** 不判断加锁结果；没有 `finally`；释放别的线程持有的锁；把锁当成事务。

**自检：** 锁粒度是否是用户？重复请求是否串行？异常是否仍释放？当前项目主路径是否真的调用锁？

**追问：** Redis Set 不是已经一人一单了吗，为什么还要锁？多实例下锁有什么价值？

### 第 6 步：写事务方法

**先写：** 按用户和优惠券查询订单；已存在则返回；条件扣 MySQL 库存；失败返回；保存 `VoucherOrder`。

**记住：** `@Transactional`、`query().eq`、条件更新、`save`。

**易错：** 只查不锁导致并发穿透；忘记检查 update 返回值；同类直接调用导致绕过代理；以为 Redis 失败会自动回滚 MySQL。

**自检：** 事务代理如何触发？哪些操作属于 MySQL 事务，哪些 Redis 操作不属于？

**追问：** 数据库没有联合唯一索引时，最后一道幂等保护是什么？

### 第 7 步：串联与失败分析

**练习：** 画出入口、Lua、Stream、消费者、锁、事务、ACK；分别模拟库存不足、重复请求、消息重复、MySQL 失败、消费者崩溃、ACK 失败。

**自检：** 对每种失败能否指出消息是否还在 Pending、Redis 是否已扣、MySQL 是否已写、是否有补偿？

## 七、面试表达策略

### 1. 30 秒项目介绍

“我在黑马点评中把优惠券秒杀从请求线程内的同步下单，优化成 Redis Lua + Redis Stream + MySQL 异步落库。入口用 Lua 原子完成 Redis 库存校验、用户购买标记和消息投递，快速失败的请求不进入数据库；后台消费者再根据消息创建订单，用数据库条件扣库存和订单查重保证数据库侧的基本安全。当前版本已经接入 Stream，但我复盘时也确认了 ACK Stream 名称和事务代理调用链仍需要进一步修正和验证，所以我会区分已实现能力和可靠性边界。”

### 2. 1～2 分钟技术展开

先说动机：同步流程让大量请求线程等待 MySQL。然后说入口：订单 ID 用 `RedisIdWorker` 提前生成，Lua 在 Redis 内原子校验库存和一人一单，成功后 `XADD` 到 `stream.orders`，HTTP 线程返回。再说异步：消费者组 `g1` 的 `c1` 读取消息，组装订单后执行 MySQL 查重、条件扣库存、保存订单。最后主动说明边界：Redis 与 MySQL 不是跨系统事务，当前代码的消费主路径直接调用事务方法且 ACK 参数与 Stream 名称不一致，Redisson 锁方法虽存在但没有被当前主消费链调用；这些是需要修复和集成验证的点，而不是已经解决的问题。

### 3. 自然引出可深入技术点

可以说：“我重点复盘了 Lua 的原子边界和 Stream 消费可靠性：Lua 只保证 Redis 内部步骤原子，订单落库仍由消费者异步完成。”这句话自然会引出 Lua、消息确认、重复消费和一致性，而不是声称系统解决了所有分布式事务问题。

### 4. 追问展开树

```text
为什么优化秒杀？
  -> 同步 MySQL 的瓶颈是什么？
  -> Redis 在入口做什么？
     -> 为什么用 Lua？原子性覆盖到哪里？
     -> Redis 库存和 MySQL 库存为什么有两份？
  -> 为什么异步？
     -> Stream、消费者组、Pending、ACK 怎么工作？
     -> 重复消费怎么办？当前代码做到哪一步？
  -> 为什么要锁？
     -> Redis Set、Redisson、数据库查重分别解决什么？
  -> 事务怎么生效？
     -> 代理调用、异步线程、失败回滚边界是什么？
```

### 5. 不会的问题怎么回答

不把未实现的重试、死信、补偿说成已有能力。可以回答：“当前代码确实有 Pending List 读取，但没有看到最大重试和死信策略；我能解释现有行为和风险，下一步会在 ACK 失败、数据库失败和重启场景补集成测试。”这比直接声称“消息最终一定成功”更准确。

## 八、高频面试追问与参考答案

### 1. 为什么需要 Lua？

**口头回答：** 把库存判断、重复购买判断、扣库存、记录用户和写消息放在 Redis 一次执行，避免并发请求在多个命令之间交错。

**技术细节：** 当前脚本返回 `0/1/2`，只使用 Redis 数据结构和 `XADD`，不访问 MySQL。

### 2. Lua 为什么具有原子性？

**口头回答：** Redis 执行脚本时会连续执行脚本中的命令，不让其他客户端命令插入这段执行过程。

**技术细节：** 它保证的是 Redis 内部脚本步骤的原子执行，不是 Redis 和 MySQL 的分布式事务。

### 3. Redis 库存与数据库库存是什么关系？

**口头回答：** Redis 库存用于高并发入口快速校验和削峰，数据库库存用于最终订单落库时的持久化扣减。

**技术细节：** 两份库存不是同一个事务；当前代码没有自动回补逻辑。

### 4. 一人一单如何保证？

**口头回答：** 入口用 Redis Set 快速拦截，数据库创建前再按用户和优惠券查重；数据库当前没有发现联合唯一索引。

**技术细节：** 当前主消费路径没有真正接入定义好的 Redisson 锁，因此不能夸大为锁已经保证并发安全。

### 5. 为什么需要异步消息？

**口头回答：** 把数据库查重、扣库存和插入订单移出 HTTP 请求线程，让入口只做快速资格校验和投递。

**技术细节：** 当前消息是 Redis Stream，不是 BlockingQueue；消费者组是 `g1`，消费者是 `c1`。

### 6. 当前消息机制的可靠性边界是什么？

**口头回答：** 当前有消费者组读取、异常进入 Pending List 的处理，但 ACK 参数与 Stream 名称不一致，且没有看到死信和最大重试机制。

**技术细节：** 读到消息不等于订单落库成功，正确 ACK 也应发生在订单处理成功之后。

### 7. 如何处理重复消费？

**口头回答：** 依赖消息中的固定订单 ID、Redis 一人一单标记和数据库按用户/优惠券查重；订单 ID 单独不够。

**技术细节：** 数据库只有 ID 主键时，业务唯一性仍需要应用逻辑或联合唯一约束。

### 8. 为什么使用 Redisson？

**口头回答：** Redisson 提供跨实例的用户维度分布式锁，设计上用于串行化同一用户的订单创建。

**技术细节：** 当前 `handleVoucherOrder` 中有该代码，但 `VoucherOrderHandler` 的实际路径没有调用它，仍需修复接入并验证释放条件。

### 9. 为什么需要事务代理？

**口头回答：** `@Transactional` 通常由 Spring 代理拦截触发，同类直接调用可能绕过代理。

**技术细节：** 当前异步线程直接调用 `createVoucherOrder`，`AopContext.currentProxy()` 只在请求方法里赋值共享字段，调用链需要修正。

### 10. Redis 成功而数据库失败怎么办？

**口头回答：** 当前代码没有看到 Redis 库存回补或用户标记撤销，因此存在一致性风险，不能说已经完整解决。

**技术细节：** 可进一步设计可靠重试、补偿或对账，但不应把这些未实现能力写成当前项目能力。

### 11. 消息积压或消费者异常怎么办？

**口头回答：** 消费者组会保留未确认消息，当前异常代码尝试读取 Pending List；但没有最大重试、死信和监控策略。

**技术细节：** 应验证 ACK 是否针对 `stream.orders`，并评估重试间隔、毒消息隔离和告警。

### 12. 如何进一步提高可靠性？

**口头回答：** 先修正同一 Stream 的 ACK、确保事务代理和锁真正进入消费链，再补充联合唯一约束、重试上限、死信、补偿和监控。

**技术细节：** 这些是后续方向，不是当前代码已经实现的能力；修复后要做 Redis/MySQL 集成测试。

### 13. 为什么不能只依赖 Redis 的一人一单？

**口头回答：** Redis 标记能挡住正常重复请求，但它和数据库订单不是一个事务，异常恢复、数据丢失或并发边界仍需要数据库侧保护。

**技术细节：** 当前数据库查重是应用查询，SQL 中未发现用户+优惠券联合唯一约束。

### 14. `XADD` 和 Java `BlockingQueue.add` 有什么区别？

**口头回答：** `XADD` 是向 Redis Stream 持久化追加记录，`BlockingQueue.add` 是当前 JVM 内存入队。

**技术细节：** Stream 支持消费者组和 Pending 语义，更适合多实例共享；当前代码运行的是 Stream 版本。

## 九、复习检查表

每次复习分成“知道答案”和“能够独立写出来”两栏，不能只看懂文档。

| 检查项 | 知道答案 | 能独立写出来 |
| --- | --- | --- |
| 不看文档画出 HTTP、Lua、Stream、MySQL 完整流程 | [ ] | [ ] |
| 解释 Lua 三个返回值和每条 Redis 命令 | [ ] | [ ] |
| 写出 `stringRedisTemplate.execute` 参数传递 | [ ] | [ ] |
| 写出 `XREADGROUP` 对应的 Spring Data Redis 代码 | [ ] | [ ] |
| 说清 Stream、消费者组、Pending、ACK | [ ] | [ ] |
| 解释 Redis 库存与 MySQL 库存的边界 | [ ] | [ ] |
| 写出用户维度 Redisson 锁和 finally 释放 | [ ] | [ ] |
| 解释事务代理与异步线程的关系 | [ ] | [ ] |
| 独立写出 MySQL 查重、条件扣库存、保存订单 | [ ] | [ ] |
| 分析库存不足、重复请求、重复消费、MySQL 失败至少三种场景 | [ ] | [ ] |
| 做 30 秒项目介绍 | [ ] | [ ] |
| 做 1～2 分钟技术展开并主动说明边界 | [ ] | [ ] |
| 回答 12 个高频追问而不夸大实现 | [ ] | [ ] |

## 十、今日难点与后续建议

- **ZSet/Redis Stream 的学习重点**：本次秒杀代码使用的是 Redis Stream，不是点赞功能中的 ZSet。这里要重点区分：Stream 负责消息记录、消费者组和 Pending；Lua 的 Redis Set 负责一人一单标记；Redis String 负责库存。
- **当前理解目标**：能够解释 Lua 原子性只覆盖 Redis 内部步骤，能够画出 Redis 先扣减、Stream 投递、MySQL 异步落库的时间顺序；不要把它表述成跨系统事务。
- **当前代码仍需验证的难点**：`acknowledge("s1", ...)` 与 `stream.orders` 不一致；消费者直接调用事务方法；Redisson 锁方法未接入实际消费主路径；`proxy` 的初始化与异步线程可见性；数据库失败后的 Redis 补偿。
- **后续建议**：在不删除现有数据的前提下准备 Redis/MySQL 集成测试，验证正常消费、重复消息、消费者重启、Pending 处理、ACK 失败和数据库异常。确认最小修复后，再单独提交代码修复，不要把未验证的可靠性能力写进面试答案。

## 十一、本次代码检查结论

- 当前实际方案：**Redis Stream**；BlockingQueue 仅存在于注释/import 和未跟踪历史文件中。
- 本次学习文档只把当前非注释调用链写成已实现，把 Redisson 锁和事务代理问题标为代码中存在但未接入/需验证。
- 未执行 Git push；代码提交与文档提交应分开，提交前只精确暂存本次秒杀相关文件，不带入用户其他改动。
