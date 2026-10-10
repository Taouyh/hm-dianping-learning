# DAY-06 学习复盘

## 1. 今日学习主题

今天复盘的是黑马点评的「关注推送 / 收件箱滚动分页」功能，重点不是背下一个最终代码，而是把下面这条业务链真正串起来：

```text
新增探店笔记
    ↓
查询作者的粉丝
    ↓
把 Blog ID 推送到每个粉丝的 Redis ZSet 收件箱
    ↓
按时间戳倒序滚动查询收件箱
    ↓
根据 Blog ID 查询 MySQL 中的 Blog 详情
    ↓
补充作者信息和当前用户的点赞状态
```

当前工作区中的关注推送代码仍然是未提交状态。Git 能证明这些代码相对于 `HEAD` 存在差异，但不能单独证明每一处都是今天由我完成的，所以这里把它记录为“当前工作区实际代码”，不把教程参考实现描述成独立设计成果。

## 2. 当前代码与 Git 差异

仓库路径：`D:\code\hm-dianping`

当前分支：`main`，与 `origin/main` 没有领先或落后提交。

当前 `HEAD` 最近的业务提交是：

```text
f39a0e4 feat(lock): implement Redis distributed lock
```

相对于 `HEAD`，关注推送相关的主要实现出现在以下未提交文件中：

- `src/main/java/com/hmdp/controller/BlogController.java`
  - `POST /blog` 委托给 `blogService.saveBlog(blog)`。
  - 新增 `GET /blog/of/follow`，接收 `lastId` 和 `offset`，调用 `blogService.blogOfFollow(max, offset)`。
- `src/main/java/com/hmdp/service/IBlogService.java`
  - 增加 `saveBlog(Blog blog)` 和 `blogOfFollow(Long max, Integer offset)` 等业务方法声明。
- `src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`
  - 新增保存 Blog 后向粉丝收件箱推送的逻辑。
  - 新增 Redis ZSet 收件箱查询和滚动分页参数计算。
  - 新增作者信息、点赞状态等 Blog 展示字段的补充逻辑。
- `src/main/java/com/hmdp/controller/FollowController.java`
  - 提供关注、取关、查询关注状态和共同关注接口。
- `src/main/java/com/hmdp/service/IFollowService.java`
  - 声明关注相关服务方法。
- `src/main/java/com/hmdp/service/impl/FollowServiceImpl.java`
  - 将关注关系写入 `tb_follow`，并把关注用户 ID 写入 `follows:<userId>` Set。

以下两个文件在当前 `HEAD` 中已经存在，本次主要是阅读和使用，不应被误认为是本次新增加的关注推送代码：

- `src/main/java/com/hmdp/dto/ScrollResult.java`
- `src/main/java/com/hmdp/utils/RedisConstants.java` 中的 `FEED_KEY = "feed:"`

另外，工作区还有秒杀、Redisson、登录等未提交或已暂存改动。本次复盘不把这些无关改动混入关注推送提交。

## 3. 实际代码位置和调用关系

### 3.1 发布 Blog 并推送

入口是 `BlogController.saveBlog`：

```java
@PostMapping
public Result saveBlog(@RequestBody Blog blog) {
    return blogService.saveBlog(blog);
}
```

真正的业务逻辑在 `BlogServiceImpl.saveBlog`：

```java
UserDTO user = UserHolder.getUser();
blog.setUserId(user.getId());
boolean isSuccess = save(blog);
if (isSuccess){
    List<Follow> followList = followService.query()
            .eq("follow_user_id", user.getId()).list();
    for (Follow follow : followList) {
        Long userId = follow.getUserId();
        String key = FEED_KEY + userId;
        stringRedisTemplate.opsForZSet().add(
                key,
                blog.getId().toString(),
                System.currentTimeMillis());
    }
}
return Result.ok(blog.getId());
```

当前实现的实际顺序是：

1. 从 `UserHolder` 获取当前登录用户。
2. 将当前用户 ID 写入 Blog 的 `userId`。
3. 先保存 Blog 到 `tb_blog`，让数据库生成 Blog ID。
4. 保存成功后，查询 `tb_follow` 中 `follow_user_id = 当前作者 ID` 的记录。这里查出来的 `follow.userId` 是作者的粉丝。
5. 对每个粉丝写入一个 Redis ZSet 收件箱。
6. ZSet member 是刚刚生成的 Blog ID，score 是当前推送时刻的毫秒时间戳。
7. 返回 Blog ID。

这里有一个容易忽略的事实：当前代码的 score 使用的是 `System.currentTimeMillis()`，而不是 `blog.getCreateTime()`。因此笔记中应说“推送时间戳”，不要直接说成“数据库创建时间”。在通常情况下两者接近，但代码并没有使用同一个字段。

### 3.2 关注关系的来源

关注入口是 `FollowController.followBlogUser`，它调用 `FollowServiceImpl.follow`。

关注时当前实现先把关注关系保存到 `tb_follow`，成功后再把被关注用户 ID 放入：

```text
follows:<当前用户 ID>
```

这个 Set 主要服务于关注关系和共同关注查询。需要区分：发布 Blog 时的推送代码没有从 `follows:<userId>` Set 取粉丝，而是直接查询 MySQL 的 `tb_follow` 表：

```java
followService.query()
        .eq("follow_user_id", user.getId()).list();
```

### 3.3 查询关注 feed

Controller 入口：

```java
@GetMapping("/of/follow")
public Result BlogOfFollow(
        @RequestParam(value = "lastId") Long max,
        @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
    return blogService.blogOfFollow(max, offset);
}
```

虽然请求参数名叫 `lastId`，但当前 Service 中的 `max` 实际上是时间戳边界，不是 Blog ID。这个命名容易造成误解。

Service 中先构造当前用户的收件箱 Key：

```java
UserDTO user = UserHolder.getUser();
Long userId = user.getId();
String key = FEED_KEY + userId;
```

`FEED_KEY` 的值是 `feed:`，所以用户 ID 为 `1001` 时，收件箱 Key 是：

```text
feed:1001
```

## 4. Push 推模式与 Redis ZSet

### 4.1 为什么新增 Blog 后要推给粉丝

关注 feed 的目标是让用户看到自己关注的博主发布的内容。当前代码采用 Push 推模式：作者发布成功后，立即把 Blog ID 写入每个粉丝的收件箱。

这样粉丝读取 feed 时，不需要先找出自己关注的所有博主，再分别查询每个博主的最新 Blog；只要读取自己的 `feed:<userId>` 即可得到候选 Blog ID。

### 4.2 Push 和 Pull 的区别

**Push（推）**：发布内容时做更多工作。

```text
作者发布 Blog
    ↓
查询粉丝
    ↓
把 Blog ID 写入每个粉丝的收件箱
```

优点是读取 feed 较快，查询时直接读自己的收件箱。缺点是一个大 V 有很多粉丝时，一次发布要写很多个收件箱，写放大明显。

**Pull（拉）**：发布时只保存作者自己的 Blog，用户读取 feed 时再查询自己关注的作者和这些作者的 Blog。

优点是发布简单，不需要给大量粉丝写入；缺点是读取时要合并多个关注对象的数据，高并发读取时压力可能更大。

当前项目的 `BlogServiceImpl.saveBlog` 明确实现的是 Push，当前关注 feed 查询也没有实现“读取关注列表后再临时合并”的 Pull 流程。

### 4.3 为什么用 ZSet 作为收件箱

Redis ZSet 同时保存 member 和 score，适合表示“某个用户收到了哪些 Blog，以及这些 Blog 的时间顺序”。当前代码的映射是：

| Redis ZSet 部分 | 当前项目中的含义 |
|---|---|
| Key | `feed:<粉丝用户 ID>`，例如 `feed:1001` |
| member | Blog ID 的字符串，例如 `"35"` |
| score | 推送时的 `System.currentTimeMillis()`，单位为毫秒 |

ZSet 会按 score 排序。查询使用倒序范围，因此可以优先得到最新推送的 Blog ID。

注意：Blog 的完整内容仍然在 MySQL 的 `tb_blog` 中。Redis 收件箱这里只保存 Blog ID 和排序分数，不是把完整 Blog 对象放进 Redis。

## 5. 收件箱滚动分页

### 5.1 当前核心代码

当前实现的核心查询是：

```java
Set<ZSetOperations.TypedTuple<String>> typedTuples =
        stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
```

Spring Data Redis 对应的含义可以理解为：

```text
按 score 从大到小查询
score 范围：0 <= score <= max
跳过 offset 条
最多取 2 条
同时返回 member 和 score
```

各参数分别是：

1. `key`：当前用户的收件箱，例如 `feed:1001`。
2. `0`：最小 score 边界，当前代码只查询非负时间戳。
3. `max`：最大 score 边界，包含等于 `max` 的记录。它是上一页返回的最小时间戳，不是页码。
4. `offset`：在当前 `max` 时间边界下，需要跳过的记录数量。它不是“第几页”，也不只是固定的每页条数。
5. `2`：本次最多取 2 条。当前项目把每页大小写死为 2。

`reverseRangeByScoreWithScores` 中的 `WithScores` 很重要。普通的按 score 查询只能拿到 member，也就是 Blog ID；这里还需要知道每条 Blog 的 score，才能计算下一页的 `minTime` 和 `offset`，所以返回 `TypedTuple<String>`。

- `typedTuple.getValue()`：获取 ZSet member，也就是 Blog ID 字符串。
- `typedTuple.getScore()`：获取该 member 对应的 score，即推送时间戳。

当前代码把 member 转成 Long 放入 `ids`，把 score 转成长整数参与滚动分页计算。

### 5.2 为什么 max 是时间戳而不是页码

普通分页通常是：

```text
第 1 页、第 2 页、第 3 页
```

但 feed 会不断产生新数据。如果用户读第一页期间又有人发布了新 Blog，使用页码分页可能导致数据重复或漏掉。滚动分页不关心“第几页”，而是记住上一次读到的时间边界：

```text
下一次查询：只看 score <= 上一次的最小 score
```

因此 `max` 表示时间位置。当前 Controller 把它命名为 `lastId`，但这个名字并不准确。

### 5.3 minTime 和 os 的计算

当前代码：

```java
long minTime = 0L;
int os = 1;
for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
    Long id = Long.valueOf(typedTuple.getValue());
    ids.add(id);

    long time = typedTuple.getScore().longValue();
    if (minTime == time){
        os++;
    } else {
        os = 1;
    }
    minTime = time;
}
os = minTime == max ? offset + os : os;
```

因为结果是按 score 从大到小排列，所以循环结束后最后一条记录的 score 就是本页最小时间戳，也就是 `minTime`。

`os` 的含义是：本次结果中，和 `minTime` 相同的记录有多少条。它不是总记录数，也不是页码。

循环过程可以这样理解：

- 第一次遇到某个时间戳时，`os = 1`。
- 下一条时间戳仍然相同，`os++`。
- 时间戳发生变化，说明开始统计新的最小时间戳，`os` 重置为 `1`。
- 因为倒序排列，最后统计到的那一组就是本页末尾的最小时间戳分组。

最后的修正：

```java
os = minTime == max ? offset + os : os;
```

含义是：

- 如果本页最小时间戳仍然等于本次查询的 `max`，说明这一页结束时仍停留在同一个时间戳边界上。由于本次查询之前已经跳过了 `offset` 条相同边界的数据，下一次还要在这个边界上继续跳过“原来的 offset + 本页新统计的 os”。
- 如果 `minTime` 已经小于原来的 `max`，说明已经进入更早的时间戳，下一次以新的 `minTime` 为边界，offset 从当前最小时间戳分组的数量重新开始，不需要把旧 offset 继续带过去。

### 5.4 相同时间戳的模拟例子

假设 Redis 中按时间倒序排列：

```text
Blog A -> 500
Blog B -> 500
Blog C -> 500
Blog D -> 400
```

每页取 2 条。

第一页请求：

```text
max = 500，offset = 0
```

返回 A、B。此时：

```text
minTime = 500
os = 2
```

因为 `minTime == max`，下一页参数应该是：

```text
max = 500，offset = 2
```

第二页会跳过 A、B，再返回 C、D。如果错误地把下一页 offset 写成 0，就会再次返回 A、B；如果把它理解成页码，也无法准确表达“同一个时间戳下已经跳过了多少条”。

第二页返回 C、D 后，最后的 `minTime = 400`，已经小于原来的 `max = 500`，所以再下一页应使用：

```text
max = 400，offset = 1
```

这里的 1 表示在 400 这个新边界上已经读过 D 一条。

### 5.5 ScrollResult 的三个字段

`ScrollResult` 当前定义为：

```java
private List<?> list;
private Long minTime;
private Integer offset;
```

它们分别表示：

- `list`：本页查询到的 Blog 列表。注意当前代码从数据库查出的列表可能比 Redis ID 列表短，因为不存在的 Blog 会被 MyBatis-Plus 查询自然过滤掉。
- `minTime`：本页最后一条 Redis 记录的 score，作为下一次请求的时间上界。
- `offset`：下一次在 `minTime` 这个时间戳边界上需要跳过的数量。

如果 Redis 没有数据，当前代码直接 `return Result.ok()`，不会返回一个包含空 list、minTime、offset 的 `ScrollResult`。这是当前实际行为，不能描述成已经统一了空结果格式。

## 6. Blog 详情、作者和点赞状态

### 6.1 为什么 Redis 只查 ID 后还要查数据库

Redis 收件箱保存的是 Blog ID，不保存完整 Blog 内容。因此拿到 ID 列表后，当前代码还要执行：

```java
String idStr = StrUtil.join(",", ids);
List<Blog> blogs = query()
        .in("id", ids)
        .last("ORDER BY FIELD (id," + idStr + ")")
        .list();
```

### 6.2 为什么要 `ORDER BY FIELD`

SQL 的：

```sql
WHERE id IN (A, B, C)
```

只表示筛选出这些记录，不保证数据库按 `A、B、C` 的顺序返回。Redis 已经根据 score 排好了 feed 顺序，如果 MySQL 返回顺序变了，前端最终看到的顺序也会变。

当前代码拼接出类似：

```sql
ORDER BY FIELD(id, 35, 31)
```

MySQL 会按照 `FIELD` 中列出的顺序返回，从而尽量保留 Redis 返回的 Blog ID 顺序。

如果某个 Blog ID 在 Redis 中存在，但 `tb_blog` 中已经不存在，当前 `.in(...).list()` 会少返回这一条，代码没有补空对象、重新查询或报错处理，后续只会对实际查出的 Blog 做作者和点赞信息补充。

### 6.3 `getUserByBlog(blog)`

当前项目没有教程中常见的 `queryBlogUser(blog)` 方法名，实际方法名是私有方法 `getUserByBlog(blog)`：

```java
private void getUserByBlog(Blog blog) {
    Long userId = blog.getUserId();
    User user = userService.getById(userId);
    blog.setName(user.getNickName());
    blog.setIcon(user.getIcon());
}
```

它读取 Blog 的 `userId`，到 `tb_user` 查询作者，再把作者昵称和头像写入 Blog 的非数据库字段：

- `Blog.name`
- `Blog.icon`

当前代码没有对 `user == null` 做保护；如果作者数据缺失，理论上可能在 `user.getNickName()` 处出现空指针。这不是本次擅自修改的对象，只记录为当前代码事实。

### 6.4 `queryIsLikedBlog(blog)`

当前项目也没有教程中常见的 `isBlogLiked(blog)` 方法名，实际方法名是私有方法 `queryIsLikedBlog(blog)`：

```java
UserDTO user = UserHolder.getUser();
if (user == null) {
    return;
}
String key = BLOG_LIKED_KEY + blog.getId();
Double score = stringRedisTemplate.opsForZSet()
        .score(key, user.getId().toString());
blog.setIsLike(score != null);
```

它的判断方式是：

- Key：`blog:liked:<blogId>`，来自 `BLOG_LIKED_KEY`。
- member：当前用户 ID 字符串。
- score：点赞时记录的时间戳；本次查询只关心 score 是否存在。
- score 不为空：当前用户点过赞，`isLike = true`。
- score 为空：没有点过赞，`isLike = false`，但当前实现只在登录用户路径中显式设置。
- 未登录：直接跳过，不查询点赞状态。

查询完 Blog 后补充这两步，是因为数据库 Blog 主表只保存 `userId`，而前端展示通常还需要作者昵称、头像和当前用户是否点赞。当前循环为每条 Blog 单独调用用户查询和点赞查询，可能产生额外开销；这可以作为后续批量查询或缓存优化方向，但本次没有进行重构。

## 7. 重难点复盘：offset 不是页码

我最容易混淆的是三个概念：

1. **查询条数**：当前代码最后一个参数 `2`，表示本次最多返回 2 条。
2. **页码**：传统分页中的第几页；当前滚动分页没有把它作为核心参数。
3. **offset**：在当前 `max` 时间戳边界下，已经跳过了多少条记录。

当不同 Blog 的 score 不同时，offset 通常会重新从当前最小时间戳分组开始统计；当多条 Blog 的 score 相同，必须继续累加旧 offset，否则下一次查询会重复读取同一时间戳中的 Blog。

所以滚动分页传递的不是“第 2 页”，而是类似：

```text
从 score <= 1700000000123 的位置开始，
在这个边界时间戳下先跳过 3 条，再取 2 条。
```

当前代码的 `os = minTime == max ? offset + os : os` 正是在处理这个边界连续的情况。结合代码逻辑，没有发现这段写法存在必然的 offset 计算错误；但由于本次没有连接真实 Redis 做多页、同分数数据验证，仍不能把它写成“已通过运行验证”。

## 8. 我的易错点

- 把 Controller 的 `lastId` 当成 Blog ID。当前 Service 的 `max` 实际是 score 时间戳。
- 把 `offset` 当成页码或固定每页条数。它表示同一时间戳边界下的跳过数量。
- 忽略 `WithScores`。没有 score，就无法计算 `minTime` 和下一页 offset。
- 忘记 ZSet 的 member 是 Blog ID，score 才是排序依据。
- 认为 MySQL 的 `IN` 会自动保持传入 ID 顺序。当前代码必须额外使用 `ORDER BY FIELD`。
- 认为 `ScrollResult.list` 一定和 Redis 返回 ID 数量相同。当前数据库记录不存在时，返回列表可能变短。
- 把 `queryBlogUser` 和 `isBlogLiked` 当成当前项目的方法名。当前真实方法是 `getUserByBlog` 和 `queryIsLikedBlog`。
- 把 Redis 收件箱当成完整 Blog 缓存。当前收件箱只存 Blog ID 和时间戳，详情仍查询 MySQL。
- 把 `FEED_KEY` 和 `follows:<userId>` 混为一谈：前者是 feed ZSet，后者是关注对象 Set。

## 9. 面试表达

### 问题 1：为什么关注推送可以使用 Redis ZSet？

参考回答：

> 每个用户维护一个自己的 feed ZSet，Key 是 `feed:<userId>`，member 是 Blog ID，score 是推送时间戳。ZSet 可以按 score 排序，使用倒序按时间取最新 Blog，并且支持按 score 做滚动分页。Blog 详情仍然在 MySQL 中查询。

### 问题 2：Push 推模式和 Pull 拉模式有什么区别？当前项目使用哪一种？

参考回答：

> Push 是作者发布时把 Blog ID 写入所有粉丝的收件箱，读取时直接查自己的 feed；Pull 是读取时先找关注关系，再合并各个关注作者的内容。当前 `BlogServiceImpl.saveBlog` 查询粉丝并写入 `feed:<粉丝 ID>`，所以是 Push 模式。

### 问题 3：`max` 和 `offset` 在滚动分页中分别表示什么？

参考回答：

> `max` 是 score 的最大边界，实际是上一次结果的最小时间戳，不是页码，也不是 Blog ID。`offset` 是在这个边界时间戳下已经跳过的记录数，用来处理多条 Blog 拥有相同 score 的情况。

### 问题 4：为什么查询 Blog 时要使用 `ORDER BY FIELD`？

参考回答：

> Redis 已经按时间顺序返回了 Blog ID，但 SQL 的 `IN` 条件不保证返回顺序。`ORDER BY FIELD(id, ...)` 按 Redis 返回的 ID 顺序排序，避免数据库查询结果打乱 feed 顺序。

### 问题 5：`WithScores` 和 `TypedTuple` 解决了什么问题？

参考回答：

> `WithScores` 让 Redis 同时返回 member 和 score。`TypedTuple.getValue()` 取得 Blog ID，`getScore()` 取得时间戳；Service 依靠 score 计算本页最小时间 `minTime` 和下一页的 offset。

## 10. 自测题

先不要看最后的答案，尝试自己写出执行过程。

1. Redis 中有 `A->500、B->500、C->500、D->400`，每页取 2 条。第一次请求 `max=500, offset=0` 返回什么？下一次请求的 `max` 和 `offset` 应该是什么？
2. 为什么 `blogOfFollow` 不能只调用 `reverseRangeByScore(key, 0, max)` 而不使用 `WithScores`？
3. 如果 Redis 返回 Blog ID `[35, 31]`，但 MySQL 的 `IN (35,31)` 返回 `[31,35]`，当前代码如何保证最终顺序仍是 `[35,31]`？如果 ID 35 在数据库不存在，当前实现会怎样？

## 11. 自测题参考答案

1. 第一次返回 A、B；本页 `minTime=500`，有 2 条记录与边界时间相同，因此下一次请求应为 `max=500, offset=2`。这样会跳过 A、B 后返回 C、D。
2. 只拿 member 可以得到 Blog ID，但拿不到每条记录的 score，因此无法确定本页最小时间戳，也无法统计最小时间戳下有多少条记录，更不能正确构造下一页的 offset。
3. 当前代码把 ID 拼成 `35,31`，在 MyBatis-Plus 查询后追加 `ORDER BY FIELD (id,35,31)`，让 MySQL 按这个顺序返回。若 35 不存在，数据库只返回实际存在的 31，当前实现不会自动补位或报错，最终 `list` 会少一条。

## 12. 尚未完全理解的内容及后续建议

目前还需要继续巩固的部分：

- 亲自用几组相同时间戳的数据模拟 `minTime`、`os` 和下一页参数，而不是只记住结论。
- 明确 `reverseRangeByScoreWithScores` 的 score 边界是包含 `max` 的，理解为什么 offset 必须和相同 score 结合。
- 进一步区分 `feed:<userId>` 收件箱和 `follows:<userId>` 关注集合。
- 观察 Blog 缺失、作者缺失和未登录用户时，当前代码的实际返回行为。
- 了解每条 Blog 逐条查询作者和点赞状态产生的 N+1 查询问题，但当前阶段只需知道问题，不要为了学习总结擅自批量重构。
- 关注 Push 模式在粉丝很多时的写放大问题，以及 Pull 模式在读取时的合并压力。

当前阶段暂时不用继续钻 Redis ZSet 的底层编码实现；先把 Key/member/score、按 score 倒序查询和滚动分页参数传递讲清楚更重要。

## 13. 实际验证情况

已完成的静态检查：

- 阅读并对比了当前工作区与 `HEAD` 的 Git 差异。
- 检查了 Blog、Follow Controller、Service、`ScrollResult`、Redis 常量、实体和 Mapper 相关代码。
- 核对了 `saveBlog` 的粉丝推送、`blogOfFollow` 的 ZSet 查询、`getUserByBlog`、`queryIsLikedBlog` 和 `ORDER BY FIELD`。
- 检查了当前分支、暂存区和未暂存区状态。

尝试执行的构建验证：

1. 直接执行 `mvn -q -DskipTests compile`：未执行成功，原因是当前 `JAVA_HOME` 指向 `D:\Java\jdk8\`，但该环境变量配置不正确。
2. 临时使用 `D:\Java\jdk17` 执行 Maven：资源复制阶段因 `target/classes/application.yaml` 的文件访问问题失败。
3. 跳过资源处理后再次编译：编译插件在写入 `target/maven-status/.../createdFiles.lst` 时失败。

因此：

- 本次没有运行单元测试。
- 没有启动 Spring Boot 服务。
- 没有连接或清理 Redis、MySQL。
- 没有通过真实数据验证 Redis ZSet 的多页滚动结果。
- “测试通过”不能写入本次总结；相关运行验证均标记为未验证。

## 14. 本阶段掌握程度判断

### 已经能够对照代码理解

- 当前项目采用 Push 推模式。
- `feed:<userId>` 是每个用户的 Redis ZSet 收件箱。
- member 是 Blog ID，score 是当前代码写入的推送时间戳。
- `max` 是时间戳边界，`offset` 用来处理同一时间戳下的连续分页。
- `WithScores`、`TypedTuple`、`minTime`、`os` 和 `ORDER BY FIELD` 各自承担的作用。
- Blog 查询后还要补充作者信息和点赞状态。

### 还需要练习到能够脱离代码复述

- 不看代码时完整说明 `os = minTime == max ? offset + os : os` 的执行过程。
- 解释为什么新 Blog 写入期间，时间戳滚动分页比页码分页更稳定。
- 在异常数据场景下说明 Blog 不存在、作者不存在、用户未登录时的当前行为。

### 本次没有证明已经掌握的内容

- 没有真实 Redis/MySQL 环境验证多页、同分数和缺失 Blog 场景。
- 没有证明这些关注推送代码是独立设计完成的；当前实现主要用于结合教程和项目代码复盘。
