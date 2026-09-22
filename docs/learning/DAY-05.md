# DAY-05 学习复盘

## 1. 今日学习主题

Redis 分布式锁基础实现。

本阶段完成了一个基于 Redis 的简单分布式锁，并把它接入优惠券同步下单流程，用来替代单机 JVM 内的 `synchronized` 用户锁。重点学习了 `SET NX EX`、锁唯一标识、误删锁和 Lua 原子释放锁。

需要明确：当前实现是学习用的基础版本，不等同于生产环境完整的分布式锁组件。

## 2. 今日实际完成

根据当前代码和 Git 工作区，今天涉及的实际功能包括：

- 新增 `ILock` 接口，定义 `tryLock(long timeoutSec)` 和 `unLock()`。
- 新增 `SimpleRedisLock`，使用 Redis 实现获取锁和释放锁。
- 获取锁时使用 `StringRedisTemplate.opsForValue().setIfAbsent(...)`，同时设置锁值和过期时间。
- 使用服务实例 UUID 前缀加线程 ID 作为锁 value。
- 新增 `src/main/resources/unlock.lua`，在 Redis 内部完成“比较锁 value + 删除锁”。
- 将 `VoucherOrderServiceImpl` 中原来的单机 `synchronized` 替换为 `SimpleRedisLock`。
- 获取锁失败时返回“一人一单”的提示；业务执行结束后在 `finally` 中释放锁。

当前实际锁名为：

```text
lock:order:<userId>
```

例如用户 ID 为 `1001` 时，锁 Key 是：

```text
lock:order:1001
```

## 3. 获取锁：实际代码做了什么

核心调用是：

```java
Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
        KEY_PREFIX + name,
        threadId,
        timeoutSec,
        TimeUnit.SECONDS
);
```

它对应 Redis 的：

```text
SET lock:order:1001 <threadId> NX EX 1200
```

各部分含义：

- `lock:order:1001`：锁 Key，表示正在竞争哪一个业务资源；
- `<threadId>`：锁 value，表示当前哪个服务实例中的哪个线程持有锁；
- `NX`：只有 Key 不存在时才写入，写入成功才代表抢锁成功；
- `EX 1200`：锁最多保留 1200 秒，避免持锁进程崩溃后锁永久存在。

`setIfAbsent(key, value, timeout, unit)` 把“写入锁”和“设置过期时间”作为一次 Redis 操作完成。相比先执行 `SETNX`、再执行 `EXPIRE`，它不会留下“锁已经写入但过期时间还没有设置”的中间状态。

## 4. 为什么锁 value 必须唯一

锁 Key 只能说明“哪一把锁”，不能说明“谁持有锁”。因此 Redis 中保存的是：

```text
lock:order:1001 -> 服务实例 UUID + 线程 ID
```

当前代码使用：

```java
private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
String threadId = ID_PREFIX + Thread.currentThread().getId();
```

其中：

- UUID 前缀用于区分不同 JVM / 服务实例；
- 线程 ID 用于区分同一个 JVM 内的不同线程。

`ID_PREFIX` 放在类成员位置，是为了在当前服务实例生命周期内保持不变。这样同一个线程加锁和解锁时，可以生成相同的标识。它不是因为“同一个用户必须使用同一个 UUID”，而是因为加锁和解锁必须使用同一个锁拥有者标识。

## 5. 误删锁：线程 A / 线程 B 场景

假设锁的有效期为 10 秒：

```text
线程 A 获取锁
    ↓
Redis: lock:order:1001 -> A 的唯一标识
    ↓
线程 A 执行时间超过 10 秒
    ↓
锁自动过期
    ↓
线程 B 获取同一把锁
    ↓
Redis: lock:order:1001 -> B 的唯一标识
    ↓
线程 A 执行 finally，准备释放锁
```

如果线程 A 直接执行：

```java
stringRedisTemplate.delete(lockKey);
```

它会把线程 B 当前持有的锁删除。线程 B 还在执行时，线程 C 就可能获得这把锁，于是多个线程同时进入临界区，分布式锁失去保护作用。

因此释放锁必须先确认：

```text
Redis 中当前的 value 是否仍然是我的 value？
```

只有相等时才允许删除。

## 6. Lua：今天最难理解的部分

当前 `unlock.lua` 的核心代码是：

```lua
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0
```

可以把它翻译成一句普通话：

> 读取这把锁当前的 value；如果它等于我传入的 value，就删除这把锁；否则什么也不删除。

### 6.1 `KEYS[1]` 是什么

`KEYS[1]` 是 Java 传给脚本的第一个 Redis Key。当前 Java 调用是：

```java
Collections.singletonList(KEY_PREFIX + name)
```

因此：

```text
KEYS[1] = lock:order:1001
```

它表示 Lua 要操作哪一个 Redis Key。

### 6.2 `ARGV[1]` 是什么

`ARGV[1]` 是 Java 传给脚本的第一个普通参数。当前传入的是：

```java
ID_PREFIX + Thread.currentThread().getId()
```

因此：

```text
ARGV[1] = 当前服务实例 UUID + 当前线程 ID
```

它表示“我认为自己持有锁时保存的 value”。

所以脚本实际上比较的是：

```text
Redis 中 lock:order:1001 的 value
是否等于当前线程的唯一标识
```

### 6.3 `redis.call()` 是什么

`redis.call()` 是 Lua 脚本中调用 Redis 命令的方式：

```lua
redis.call('get', KEYS[1])
```

相当于执行：

```text
GET lock:order:1001
```

而：

```lua
redis.call('del', KEYS[1])
```

相当于执行：

```text
DEL lock:order:1001
```

### 6.4 为什么不能拆成 Java 的两条命令

看起来可以写成：

```java
String value = stringRedisTemplate.opsForValue().get(key);
if (threadId.equals(value)) {
    stringRedisTemplate.delete(key);
}
```

但这实际上是两次独立的 Redis 请求：

```text
第一次请求：GET
第二次请求：DEL
```

在线程 A 执行完 `GET` 后，到执行 `DEL` 之前，可能发生：

```text
线程 A GET：发现 value 是 A
    ↓
锁过期
    ↓
线程 B 获得锁，Redis value 变成 B
    ↓
线程 A 执行 DEL
    ↓
线程 A 删除了线程 B 的锁
```

Java 中的 `if` 只能保证当前 Java 线程的判断逻辑连续，不能保证两次 Redis 命令之间没有其他客户端命令插入。

### 6.5 Lua 为什么能解决这个问题

Lua 把下面两步放入 Redis 内部一次执行：

```text
读取锁 value
    ↓
判断是否属于当前线程
    ↓
符合条件才删除
```

Redis 执行 Lua 脚本时，会把脚本作为一个整体执行。在脚本执行期间，其他 Redis 命令不会插入到 `GET` 和 `DEL` 之间。因此线程 A 的“判断”和“删除”之间不存在被线程 B 插入的窗口。

这就是这里所说的原子性：

> 对其他 Redis 客户端来说，要么看到脚本执行前的状态，要么看到脚本执行后的状态，不会看到脚本中间的半完成状态。

Lua 并不是让 Java 代码变快，也不是自动解决所有分布式问题；它解决的是“校验锁归属”和“删除锁”必须作为一个不可拆分过程执行的问题。

## 7. 当前实际调用链

```text
POST /voucher-order/seckill/{voucherId}
        ↓
VoucherOrderController
        ↓
VoucherOrderServiceImpl.seckillVoucher
        ↓
创建 SimpleRedisLock("order:" + userId)
        ↓
SET lock:order:<userId> <实例UUID-线程ID> NX EX 1200
        ↓
获取失败：返回一人一单提示
        ↓
获取成功：执行查询订单、扣库存、创建订单
        ↓
finally 调用 unLock()
        ↓
执行 unlock.lua
        ↓
比较 Redis value 与当前线程标识
        ↓
相同才 DEL，不同则不删除
```

## 8. 当前实现的局限

### 必须关注的问题

- 当前锁的 TTL 设置为 1200 秒；如果业务执行时间超过 TTL，锁可能提前过期，仍存在其他线程进入临界区的风险。
- 当前实现没有自动续期机制。
- 一人一单最终仍建议使用数据库唯一索引兜底，不能只依赖 Redis 锁。

### 可以优化的问题

- `SimpleRedisLock` 的 `name` 和 `StringRedisTemplate` 可以使用更明确的不可变字段设计。
- 可以统一锁 Key 常量和命名规则。
- 可以对 Lua 返回值进行日志或测试验证，例如 `1` 表示删除成功、`0` 表示锁不属于当前线程或锁已不存在。
- 方法名 `unLock` 可以按 Java 常见命名习惯改为 `unlock`，但当前阶段不必为了命名单独重构。

### 当前阶段可以暂时不管

- Redisson 看门狗；
- 可重入锁；
- Redis Cluster 下的完整锁路由和故障处理；
- 主从复制延迟、故障转移和 RedLock；
- 高并发下锁竞争的性能调优。

## 9. 面试重点

### Level 1：必须会

1. **什么是分布式锁？**
   多个服务实例共同访问共享资源时，用一个所有实例都能看到的锁，保证同一时刻只有符合条件的一个执行者进入临界区。

2. **Redis 为什么可以实现分布式锁？**
   Redis 的 Key 对所有客户端可见，且支持带条件的写入和过期时间，可以用一个 Key 表示锁状态。

3. **`SET NX EX` 是什么意思？**
   `NX` 表示 Key 不存在时才写入；`EX` 表示设置秒级过期时间。两者配合可以原子地“抢锁并设置自动释放时间”。

4. **为什么需要唯一 value？**
   用来标识锁的拥有者。释放锁时必须确认当前锁仍属于自己，避免误删别人刚获得的锁。

5. **为什么不能直接 `DEL key`？**
   因为锁可能已经过期并被别人重新获得，直接删除会删除别人的锁。

6. **Lua 在这里解决什么问题？**
   把“判断 value 是否属于自己”和“删除 Key”放在 Redis 内部一次执行，避免两条命令之间被其他客户端插入。

### Level 2：应该理解

- Redis 单线程执行命令；Lua 脚本中的 Redis 操作按脚本整体执行，不会在脚本内部被其他命令插入。
- `SETNX + EXPIRE` 的风险是两条命令之间进程宕机，可能留下没有过期时间的永久锁。
- 分布式锁生命周期是：尝试获取 → 成功后执行业务 → 正常释放，或 TTL 到期自动释放。
- 锁 value 是“谁持有锁”，锁 Key 是“锁住哪个资源”，两者职责不同。
- 分布式锁解决的是跨 JVM、跨进程协调问题，不能自动保证数据库事务、业务幂等和所有故障场景。

### Level 3：后续学习

- Redisson 的可重入锁；
- 看门狗自动续期；
- Redis Cluster 环境中的分布式锁；
- 主从复制和故障转移下的锁安全；
- RedLock；
- 高并发锁竞争、锁粒度和性能问题。

这些内容不标记为今天已经掌握。

## 10. 面试自测

1. Redis 分布式锁和 `synchronized` 的适用范围有什么区别？
2. `SET key value NX EX 10` 的每个部分是什么意思？
3. 为什么获取锁时 value 不能简单写成固定的 `1`？
4. 线程 A 的锁过期后，线程 B 获得锁，线程 A 为什么不能直接 `DEL`？
5. 为什么释放锁不能拆成 Java 的 `GET + if + DEL`？
6. `KEYS[1]` 和 `ARGV[1]` 在当前 Lua 脚本中分别代表什么？
7. `redis.call('get', KEYS[1])` 和 `redis.call('del', KEYS[1])` 分别做什么？
8. Lua 为什么能保证“判断锁归属 + 删除锁”不被其他 Redis 命令插入？
9. `SETNX + EXPIRE` 相比 `SET NX EX` 存在哪种异常风险？
10. 如果业务执行时间超过锁 TTL，当前基础实现会有什么问题？

## 11. 今日掌握程度

### 🟢 独立完成

目前没有足够证据把 Redis 分布式锁完整实现标记为独立设计完成。

### 🟡 理解后完成

- 能根据代码说明 `setIfAbsent`、锁 Key、锁 value 和 TTL 的基本作用。
- 能说明为什么锁 value 要包含服务实例标识和线程标识。
- 能理解并解释 Lua 中 `KEYS[1]`、`ARGV[1]`、`redis.call()` 的作用。
- 能说明 Java 两条 Redis 命令之间存在误删锁窗口。
- 能说明 Lua 在本场景中提供的是判断与删除的原子性。

### 🟠 参考教程完成 / 待掌握

- `SimpleRedisLock` 和 Lua 释放锁的整体代码结构主要参考课程实现。
- 分布式锁在集群、故障恢复和长业务场景下的工程化设计还需要继续学习。

### 🔴 目前不会

- 尚未实现或验证 Redisson 看门狗、可重入锁和续期机制。
- 尚未深入验证 Redis Cluster、主从复制和 RedLock 的安全边界。
- 尚未完成高并发分布式锁压力测试和故障注入测试。

## 12. 今日遗留问题

- 锁 TTL 到期但业务尚未结束时如何自动续期？
- 如何设计可重入分布式锁？
- 多个 Redis 节点、主从切换时，锁是否仍然安全？
- 数据库唯一索引、Redis 锁和业务事务如何共同保证一人一单？
- 如何使用 Redisson 替代手写锁，并理解它解决了哪些边界问题？

## 13. Git 检查与提交建议

当前 `git status` 显示：

```text
M  src/main/java/com/hmdp/service/ISeckillVoucherService.java
M  src/main/java/com/hmdp/service/impl/SeckillVoucherServiceImpl.java
M  src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java
AM src/main/java/com/hmdp/utils/ILock.java
AM src/main/java/com/hmdp/utils/SimpleRedisLock.java
AM src/main/resources/unlock.lua
?? README.md
```

其中：

- `VoucherOrderServiceImpl.java`、`ILock.java`、`SimpleRedisLock.java`、`unlock.lua` 是今天分布式锁功能的相关文件；
- `ILock.java`、`SimpleRedisLock.java`、`unlock.lua` 的 `AM` 表示索引中仍是旧骨架，工作区才是完整实现；后续提交前必须重新暂存完整文件；
- `ISeckillVoucherService.java` 和 `SeckillVoucherServiceImpl.java` 当前只有无效 import / 空行变化，没有分布式锁业务改动，建议不纳入本次提交；
- `README.md` 是未跟踪文件，建议排除；
- 当前未发现配置文件、`.env` 或 IDE 配置被本任务修改。

建议提交文件：

```text
src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java
src/main/java/com/hmdp/utils/ILock.java
src/main/java/com/hmdp/utils/SimpleRedisLock.java
src/main/resources/unlock.lua
docs/learning/DAY-05.md
```

建议 commit message：

```text
feat(lock): implement Redis distributed lock
```

本次只准备本地 commit，不执行 `git push`。
