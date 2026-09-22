# DAY-04 学习复盘

## 1. 今日主题

本阶段学习并完成优惠券秒杀的基础同步下单流程，重点理解一人一单、悲观锁、乐观锁和 Redis 分布式 ID。本阶段尚未实现 Redis Lua、Redis Stream 和异步下单。

## 2. 今日完成的功能

- `POST /voucher-order/seckill/{id}` 从“功能未完成”改为调用订单 Service。
- 校验秒杀开始时间、结束时间和库存。
- 获取当前登录用户，并按用户加锁后执行订单创建。
- 查询 `user_id + voucher_id` 是否已有订单，拒绝重复购买。
- 使用 `stock = stock - 1` 且 `stock > 0` 的条件更新扣减数据库库存。
- 生成并保存 `VoucherOrder`，同步返回订单 ID。
- 将 `RedisIdWorker` 交给 Spring 管理，使用 Redis 自增序列和时间戳生成订单 ID。
- 增加多线程生成 ID 的测试。

## 3. 一人一单的核心思路

一人一单表示：同一个 `user_id + voucher_id` 组合只能有一笔订单，并不是一个用户只能购买一张优惠券。

当前流程是：

```text
获取用户
  ↓
按用户加锁，让同一用户的请求串行执行
  ↓
查询该用户是否已经购买这张券
  ↓
已购买则失败，未购买则继续扣库存并创建订单
```

如果只查询订单、不加锁，两个并发请求可能同时查到“没有订单”，随后都创建订单。因此“查询是否购买过”和“创建订单”之间必须有并发控制。

当前使用 `synchronized (userId.toString().intern())`，它只在单个 JVM 内有效；如果项目部署多个实例，需要 Redis 分布式锁、数据库唯一索引等方案兜底。

## 4. 悲观锁和乐观锁

### 悲观锁

悲观锁假设冲突很可能发生，先锁住临界区再处理。当前 `synchronized` 是进程内悲观锁的一种表现：同一用户的请求排队执行。它容易理解，但会让请求等待，而且不能协调多个 JVM。

### 乐观锁

乐观锁假设冲突不一定发生，在更新时再检查条件。当前库存更新的核心是：

```sql
UPDATE tb_seckill_voucher
SET stock = stock - 1
WHERE voucher_id = ? AND stock > 0
```

更新成功说明扣减前库存仍然大于 0；更新失败说明库存不足或记录不存在。它属于条件更新/CAS 思路，不是带 `version` 字段的完整版本号乐观锁。

两类并发控制保护的问题不同：

```text
用户锁：防止同一用户重复下单
stock > 0：防止不同用户抢最后库存时发生超卖
```

只使用其中一种都不够。

## 5. RedisIdWorker

订单 ID 使用“时间戳 + Redis 自增序列”组合生成：高位表示相对起始时间的秒数，低位表示 Redis 按业务和日期递增的序列号。Redis `INCR` 具备原子自增特性，因此并发生成时不会得到相同序列号。

当前同步秒杀中，Redis 只参与订单 ID 生成；库存和订单仍由 MySQL 同步处理。

## 6. 当前实际请求流程

```text
POST /voucher-order/seckill/{voucherId}
  ↓
VoucherOrderController
  ↓
VoucherOrderServiceImpl.seckillVoucher
  ↓
查询秒杀券、校验时间和库存
  ↓
按用户加锁
  ↓
查询是否已有订单
  ↓
MySQL 条件扣减库存
  ↓
Redis 生成订单 ID
  ↓
MySQL 保存 VoucherOrder
  ↓
同步返回订单 ID
```

当前还没有 Redis 预扣库存、Lua 原子脚本、Redis Stream、异步消费者和失败补偿流程。

## 7. 真实问题与后续边界

- 当前用户锁只适用于单机 JVM，集群环境需要分布式锁或数据库唯一索引。
- 查询订单、扣库存、保存订单要求正确的事务边界；内部方法调用是否经过 Spring 事务代理需要继续验证。
- 秒杀券 ID 不存在时，当前代码需要补充空值边界处理。
- 同步方案仍会给 MySQL 带来较大并发压力，下一阶段应学习 Redis + Lua 的资格判断和异步下单。

## 8. 掌握程度

### 🟢 独立完成

目前没有足够证据确认整体秒杀方案由我完全独立设计，因此不标记为独立完成。

### 🟡 理解后完成

- 理解 Controller、Service、秒杀券、库存和订单之间的基本调用关系。
- 理解一人一单的业务含义和查询与创建之间需要并发控制的原因。
- 理解用户锁与 `stock > 0` 条件更新分别解决什么问题。
- 理解 Redis 自增序列参与生成订单 ID 的基本思路。

### 🟠 参考教程完成 / 待掌握

- 同步秒杀下单的整体结构主要参考课程实现。
- RedisIdWorker 的时间戳、位运算、序列位数和并发验证还需要继续练习。

### 🔴 目前不会

- 尚未独立实现 Redis Lua 原子校验库存和一人一单。
- 尚未实现 Redis Stream 消息队列和异步下单消费者。
- 尚未完整处理 Redis 与 MySQL 库存不一致、消息失败和补偿问题。

## 9. 面试知识点

- 一人一单为什么是 `user_id + voucher_id` 的唯一组合。
- `synchronized` 的作用范围，以及它为什么不能解决集群并发。
- 悲观锁与乐观锁的假设、优缺点和适用场景。
- `UPDATE ... WHERE stock > 0` 为什么可以避免库存扣成负数。
- 查询库存后再扣库存为什么会产生超卖。
- Redis 自增、时间戳和位运算如何生成分布式唯一 ID。
- 同步下单和后续 Redis + Lua + 异步下单的区别。

## 10. 今日创新点

今日暂无独立创新点。

## 11. Git 提交准备

本阶段建议提交：

```text
src/main/java/com/hmdp/controller/VoucherOrderController.java
src/main/java/com/hmdp/service/ISeckillVoucherService.java
src/main/java/com/hmdp/service/IVoucherOrderService.java
src/main/java/com/hmdp/service/impl/SeckillVoucherServiceImpl.java
src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java
src/main/java/com/hmdp/utils/RedisIdWorker.java
src/test/java/com/hmdp/HmDianPingApplicationTests.java
docs/learning/DAY-04.md
```

排除未跟踪的 `README.md`，不执行 `git push`。

建议 commit message：

```text
feat(seckill): implement synchronous voucher ordering
```
