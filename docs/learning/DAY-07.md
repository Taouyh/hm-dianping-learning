# DAY-07 学习复盘：关注关系与共同关注

## 1. 今日学习主题

今天整理的是「关注 / 取关、关注状态查询、共同关注」这组业务。重点是理解关注关系在 MySQL 和 Redis 中分别承担什么作用，以及共同关注如何利用 Redis Set 的交集查询。

说明：本仓库中关注推送和收件箱滚动分页已经在此前提交中实现；本篇聚焦当前工作区里新增的共同关注接口，并回顾它依赖的关注集合。这里描述的是当前项目代码，不把教程中的方案说成独立设计。

## 2. 实际代码位置与本次变更边界

仓库：`D:\code\hm-dianping`

本次计划提交的关注关系代码：

- `src/main/java/com/hmdp/controller/FollowController.java`：新增 `GET /follow/common/{id}` 接口，转调 Service。
- `src/main/java/com/hmdp/service/IFollowService.java`：新增 `commonFollow(Long followUserId)` 服务声明。
- `src/main/java/com/hmdp/service/impl/FollowServiceImpl.java`：新增 Redis Set 求交集、查询用户并转换为 `UserDTO` 的实现。

此前已提交的相关基础代码（不是本次新写）：

- `follow(...)` 在关注成功后把被关注用户 ID 放入 `follows:<当前用户ID>` Set，取关成功后从 Set 删除。
- 关注关系本身保存在 MySQL 的 `tb_follow` 表。
- Blog 发布后的粉丝推送和收件箱滚动查询已由此前关注推送提交实现，和本次共同关注接口不是同一段逻辑。

工作区还存在秒杀、Redisson、Blog/用户等其他改动。本次提交只选择上述三个关注相关文件和本学习文档，不把其他改动混入。

## 3. 调用流程

```text
GET /follow/common/{id}
        ↓
FollowController.commonFollow(id)
        ↓
IFollowService.commonFollow(id)
        ↓
FollowServiceImpl.commonFollow(id)
        ↓
取当前登录用户 ID
        ↓
求 follows:<目标用户ID> 与 follows:<当前用户ID> 的交集
        ↓
把交集中的用户 ID 转为 Long
        ↓
按 ID 查询 User，并转换为 UserDTO
        ↓
Result.ok(共同关注用户列表)
```

这里的 `id` 是要查看其关注关系的目标用户 ID；当前用户由 `UserHolder` 获取。共同关注的含义是：当前用户和目标用户都关注了哪些用户。

## 4. 关注集合如何维护

`FollowServiceImpl.follow(followUserId, isFollow)` 中：

- 当前用户 ID 来自 `UserHolder.getUser()`。
- Redis Set Key 是 `follows:<当前用户ID>`。
- 关注时，先向 `tb_follow` 保存关系；保存成功后，将 `followUserId` 作为字符串 member 加入 Set。
- 取关时，先从 `tb_follow` 删除关系；删除成功后，从 Set 移除对应 member。

因此 MySQL 保存关系记录，Redis Set 为共同关注等集合运算提供快速数据结构。当前代码是“数据库操作成功后再更新 Redis”，但并未实现跨 MySQL/Redis 的分布式事务或失败补偿；若 Redis 更新失败，两个存储可能短暂或持续不一致，这是当前实现的边界，不应误说成已解决。

## 5. 共同关注实现逐步看

`commonFollow(followUserId)` 的关键逻辑可概括为：

```java
UserDTO currentUser = UserHolder.getUser();
if (currentUser == null) {
    return Result.fail("未登录，无法查看");
}
Long userId = currentUser.getId();
String key1 = "follows:" + followUserId;
String key2 = "follows:" + userId;
Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
```

1. 先确认当前请求有登录用户；没有则返回失败结果。
2. `key1` 表示目标用户关注的用户集合，`key2` 表示当前用户关注的用户集合。
3. Redis `SINTER` 求两个 Set 的交集。交集中的每个 member 是双方共同关注的用户 ID（字符串）。
4. 若交集为 `null` 或空，返回空列表，避免对空 ID 集合继续查询。
5. 将字符串 ID 转为 `Long`，调用 `userService.listByIds(ids)` 查询用户实体。
6. 将实体转成接口返回使用的 `UserDTO`，而不是直接把完整 `User` 实体返回。

控制器路径由类级别 `@RequestMapping("/follow")` 与方法级别 `@GetMapping("/common/{id}")` 组合得到。具体请求为 `GET /follow/common/{id}`。

## 6. Redis、Lua/收件箱与数据库的边界

本功能没有调用 Lua，也没有使用 `feed:<userId>` ZSet。不要把“共同关注”和“关注推送收件箱”混在一起：

- `follows:<userId>`：Redis Set，member 是该用户关注的用户 ID，用于关注集合运算。
- `feed:<userId>`：关注推送功能的收件箱 ZSet，member 是 Blog ID，score 是推送时间戳，用于按时间滚动读取 Blog。
- `tb_follow`：MySQL 中持久化关注关系。
- `userService.listByIds(...)`：共同关注求出 ID 后，再从用户数据中读取展示信息。

共同关注本身只依赖 Set 交集，不涉及滚动分页中的 `max`、`offset`、ZSet score 或 `ScrollResult`。

## 7. 当前实现的行为与需要留意之处

- 未登录：返回 `Result.fail("未登录，无法查看")`。
- 两个 Set 没有交集：返回空列表。
- 有交集：查询对应 User 并映射成 UserDTO。
- Redis Set 若尚未建立或与 `tb_follow` 不一致，当前方法没有回源 MySQL 重建 Set 的逻辑；结果可能不完整。
- `SINTER` 的返回是 Set，当前实现转成 List 时不承诺稳定顺序；共同关注列表没有显式排序。
- `listByIds` 返回的数据库记录顺序也不应被当作与输入 IDs 一致，当前共同关注实现没有额外排序要求或排序代码。
- 当前代码逐次更新 MySQL 和 Redis，未实现跨存储原子一致性、重试或补偿机制。

这些是阅读当前实现得出的行为和风险，不代表已经针对它们做过修复或压测。

## 8. 今日易错点

1. **共同关注不是“我关注目标用户”**：它是两个关注集合的交集，集合元素是双方都关注的第三方用户。
2. **方向不要颠倒**：`follows:<用户ID>` 的 member 是“这个用户关注了谁”，因此求交集的两个 Key 分别使用目标用户 ID 与当前用户 ID。
3. **Redis Set 和收件箱 ZSet 是不同用途**：`follows:` 做集合关系；`feed:` 做 Blog 时间序列查询。
4. **Redis 不是关注关系的唯一持久来源**：当前关注动作先写 MySQL，再更新 Set；但共同关注读取直接依赖 Redis Set。
5. **不要声称接口结果有序**：代码没有对交集结果做排序。

## 9. 面试表达

### 问题一：共同关注接口是怎么实现的？

参考回答：当前用户和目标用户的关注用户 ID 分别保存在 `follows:<userId>` Redis Set 中。服务端用 Redis 的 Set 交集求出共同关注 ID，再查询用户信息并转换为 `UserDTO` 返回。项目入口是 `FollowController.commonFollow`，实现位于 `FollowServiceImpl.commonFollow`。

### 问题二：为什么用 Redis Set 存关注用户 ID？

参考回答：关注关系天然是一个去重集合，Set 支持成员增删以及交集运算。共同关注可以用两个集合的交集实现；关系记录仍保存在 MySQL，Redis Set 是面向集合查询的辅助数据。

### 问题三：MySQL 和 Redis 同时更新，如何保证一致？

参考回答：当前代码先执行 MySQL 关注/取关，成功后再更新 Redis Set，但这不是跨库事务；Redis 操作失败会留下不一致可能。当前实现没有补偿或重建逻辑，生产方案通常还要结合重试、消息/任务补偿或缓存重建等设计。

### 问题四：共同关注和关注推送有什么区别？

参考回答：共同关注用 `follows:` Set 求两个用户关注集合的交集；关注推送则在作者发布 Blog 后把 Blog ID 写入粉丝的 `feed:` ZSet，再按 score 查询收件箱。两者数据结构、写入时机和查询目标都不同。

## 10. 自测题

建议先遮住文末答案自行回答。

1. 用户 A 和用户 B 的 Redis Key 分别是 `follows:10`、`follows:20`，其中分别保存字符串 ID `31, 32` 与 `32, 33`。接口应返回谁？请按代码顺序说明从 Redis member 到响应 DTO 的过程。
2. 关注写入 `tb_follow` 成功，但 Redis `SADD` 失败，随后调用共同关注接口可能发生什么？当前代码做了哪些处理、哪些没有处理？
3. 为什么 `follows:10` 的 member 不能理解成“关注 10 的粉丝”？如果需要查询 10 的粉丝，当前关注记录中的哪个字段关系代表粉丝？

### 自测题参考答案

1. 交集是用户 ID `32`。Redis 返回字符串 member，代码转换为 Long，调用 `userService.listByIds(ids)` 查询 User，再复制到 UserDTO 返回。Set 和 `listByIds` 都没有在当前实现中保证特定顺序。
2. MySQL 已有关系，但 Redis Set 缺少 member；共同关注读取直接做 Redis 交集，可能漏掉该共同关注用户。当前代码只在 Redis 操作中执行 add，没有重试、事务或从 MySQL 回源修复。
3. `follows:<userId>` 的 Set 是该用户主动关注的对象列表。粉丝关系相反：在 `tb_follow` 中查 `follow_user_id = 10`，对应记录的 `user_id` 是关注 10 的用户。

## 11. 后续学习建议

- 暂时不用继续钻 Redis Set 的内部编码；先能解释 `SADD`、`SREM`、`SINTER` 与当前业务映射即可。
- 下一步可以结合 `tb_follow` 字段，自己画出“关注者 → 被关注者”和“作者 → 粉丝”的方向图。
- 再通过代码演练 Redis Set 丢失或更新失败时的表现，讨论缓存重建与一致性；先分析，不急着重构。
- 关注推送滚动分页的 `max/offset` 是独立主题，不要混入本次共同关注实现。

## 12. 验证情况

- 已做静态代码阅读，并对照 Git 差异核对控制器、Service 接口和实现。
- 未运行单元测试或 Maven 构建。
- 未启动服务，未连接 Redis 或 MySQL，未进行接口端到端验证。
- 因此本篇不记录“测试通过”；共同关注查询和跨存储一致性未做运行时验证。
