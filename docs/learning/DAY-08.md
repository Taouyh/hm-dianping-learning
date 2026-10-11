# DAY-08 学习总结：达人探店点赞、点赞用户查询与顺序保持

## 1. 今日学习主题与检查结论

今天整理的是当前工作区中「达人探店」相关的 Blog 代码，重点放在：

- 用户对探店笔记点赞、取消点赞，以及当前用户点赞状态的判断；
- 查询某篇探店笔记的点赞用户；
- Redis ZSet 保存点赞用户和点赞时间；
- Redis 返回用户 ID 后，如何通过数据库查询补充用户信息并保持顺序；
- `ORDER BY FIELD(id, ...)` 与 Java `Map` 重排两种思路的区别。

先对当前仓库做了静态检查：

- 项目根目录没有发现 `AGENTS.md`，因此没有额外的项目级代理规范可遵循。
- 当前分支是 `main`，HEAD 为 `d3f0bf7 feat(follow): add common follow query`。
- 暂存区为空；工作区还有多组未提交改动，其中包含秒杀、Redisson、用户、关注推送等内容。
- 本文只记录当前真实存在的达人探店代码，不把教程中的未实现内容写成已经实现，也不把之前已经提交的关注推送、滚动收件箱和共同关注重新作为本次功能提交。

当前与达人探店直接相关的代码位置：

- `src/main/java/com/hmdp/controller/BlogController.java`
- `src/main/java/com/hmdp/service/IBlogService.java`
- `src/main/java/com/hmdp/service/impl/BlogServiceImpl.java`
- `src/main/java/com/hmdp/entity/Blog.java`
- `src/main/java/com/hmdp/entity/User.java`
- `src/main/java/com/hmdp/dto/UserDTO.java`
- `src/main/java/com/hmdp/utils/RedisConstants.java`

其中，`Blog` 实体中的 `icon`、`name`、`isLike` 是非数据库字段，用于接口返回补充信息；本轮工作区 diff 没有修改实体类本身。

## 2. 当前代码实际实现了什么

### 2.1 接口入口

当前 `BlogController` 实际包含以下与达人探店有关的入口：

- `POST /blog`：调用 `saveBlog` 发布探店笔记。当前代码还会把新笔记推送到粉丝的 `feed:<粉丝ID>` ZSet；这部分关注推送已经属于此前功能边界，本篇只在必要处提及，不作为本次新增提交内容。
- `PUT /blog/like/{id}`：调用 `queryIsLikeBlog(id)`。方法名虽然包含 `query`，但实际行为是“点赞/取消点赞切换”，不是只读查询。
- `GET /blog/hot`：分页查询点赞数较高的 Blog，并补充作者信息和当前用户点赞状态。
- `GET /blog/{id}`：查询单篇 Blog，补充作者信息和当前用户点赞状态。
- `GET /blog/likes/{id}`：查询某篇 Blog 的点赞用户列表。
- `GET /blog/of/user?id=...`：分页查询某个用户发布的 Blog。当前实现只查询 Blog 记录，没有像详情和热门列表那样补充作者信息或 `isLike`。
- `GET /blog/of/follow`：从关注收件箱滚动查询 Blog，并使用 `ORDER BY FIELD` 保持 Redis 返回的 Blog ID 顺序；这属于此前关注推送功能，本篇只用它帮助理解同一种顺序保持方式。

### 2.2 点赞与取消点赞

实际代码位于 `BlogServiceImpl.queryIsLikeBlog(Long id)`：

1. 从 `UserHolder.getUser()` 获取当前登录用户 ID。
2. 拼出 Redis Key：

   ```text
   blog:liked:<blogId>
   ```

   例如 Blog ID 为 `100` 时，Key 是 `blog:liked:100`。

3. 通过 `ZSetOperations.score(key, userId)` 判断当前用户是否已经是这个 ZSet 的 member。
4. 如果查询结果为 `null`，当前用户还没有点赞：
   - MySQL 中执行 `liked = liked + 1`；
   - 数据库更新成功后，将当前用户 ID 作为字符串 member 写入 ZSet；
   - score 使用 `System.currentTimeMillis()`，即点赞发生时的毫秒时间戳。
5. 如果查询结果不为 `null`，当前用户已经点赞：
   - MySQL 中执行 `liked = liked - 1`；
   - 数据库更新成功后，从该 Blog 的 ZSet 中移除当前用户 ID。

因此，当前项目的职责划分是：

- **MySQL 的 `tb_blog.liked`**：保存这篇笔记的点赞总数，用于热门 Blog 的按点赞数分页查询。
- **Redis 的 `blog:liked:<blogId>` ZSet**：保存“哪些用户点过这篇 Blog”以及每次用户点赞的时间分数，用于判断点赞状态和读取点赞用户顺序。

当前代码是先更新数据库，只有数据库更新成功后才更新 Redis。两次操作之间没有跨 MySQL 和 Redis 的事务、重试或补偿，因此不能把它描述成已经解决了跨存储一致性问题。如果数据库更新成功但 Redis 操作失败，点赞总数和点赞用户 ZSet 可能短暂或持续不一致。

### 2.3 当前用户是否点赞

当前用户是否点过赞的只读判断位于 `queryIsLikedBlog(Blog blog)`：

```java
String key = BLOG_LIKED_KEY + blog.getId();
Double score = stringRedisTemplate.opsForZSet()
        .score(key, user.getId().toString());
blog.setIsLike(score != null);
```

判断依据不是 MySQL 的 `liked` 总数，而是“当前用户 ID 是否存在于这篇 Blog 对应的 ZSet 中”：

- `score == null`：当前用户不在 ZSet 中，`isLike` 设置为 `false`；
- `score != null`：当前用户是 ZSet member，`isLike` 设置为 `true`。

如果请求未登录，当前方法直接返回，不设置 `isLike`。因此，未登录场景下不能从这段代码推断出一定会返回 `false`，具体结果还取决于 Blog 对象的初始值和序列化行为。

## 3. 为什么点赞功能使用 Redis ZSet

### 3.1 必须区分两个概念

这里有两个容易混淆的概念：

1. **点赞状态判断**：判断当前用户是否点过某篇 Blog。这个需求只需要判断某个 user ID 是否存在。
2. **点赞用户列表的顺序**：查询某篇 Blog 的点赞用户时，希望按照 ZSet score 对用户 ID 排序。

普通 Set 很适合第一个需求，因为 Set 可以快速判断 member 是否存在，也可以执行添加和删除。但普通 Set 没有为每个 member 保存 score，不能直接表达“谁先点赞、谁后点赞”，也不能直接按点赞时间范围查询。

ZSet 同时具备：

- member 的唯一性，适合保存用户 ID；
- member 对应的 score，适合保存点赞时间戳；
- 按 score 的范围查询和排序能力。

### 3.2 当前项目中 member 和 score 的含义

对于某个 Blog，Key 是：

```text
blog:liked:<blogId>
```

ZSet 中的字段含义是：

| ZSet 部分 | 当前项目中的含义 | 示例 |
|---|---|---|
| member | 点赞用户 ID，代码以字符串写入 | `"5"` |
| score | 该用户点赞时的毫秒时间戳 | `1710000000123` |

例如：

```text
blog:liked:100
  member="5", score=100
  member="2", score=200
  member="9", score=300
```

这表示用户 5、2、9 点过 Blog 100，数字越大的 score 表示点赞时间越晚。时间戳可以作为 score，是因为它是可比较的数值，并且点赞发生时间天然可以转换为毫秒数。

### 3.3 `add`、`remove`、`score`、`range` 的实际用途

当前代码中的 Redis 操作对应关系如下：

- `opsForZSet().add(key, userId, System.currentTimeMillis())`：点赞成功后，添加用户 member，并记录点赞时间。
- `opsForZSet().remove(key, userId)`：取消点赞成功后，删除该用户 member。
- `opsForZSet().score(key, userId)`：判断当前用户是否已经点赞，也就是判断该 member 是否存在。
- `opsForZSet().range(key, 0, 4)`：查询排序后的前 5 个 member。

需要特别注意：当前项目的 `queryBlogLikes` 使用的是 `range`，即按 score **从小到大**取前 5 个 member。因为当前 score 是点赞时间戳，所以它实际更接近“较早点赞的前五位”，而不是“最新点赞的前五位”。代码虽然把结果变量命名为 `top5`，也注释为“点赞排行榜”，但它不是按点赞数量排名的全局排行榜，也没有使用 `reverseRange` 获取最新点赞者。

因此，准确的说法是：当前项目使用每篇 Blog 的点赞记录 ZSet，利用用户 ID member 和点赞时间 score 支持点赞状态判断及按 score 读取点赞用户；它不是按用户获得点赞数量排名的排行榜。

### 3.4 为什么不能把它说成按点赞数量排名

`blog:liked:<blogId>` 这个 ZSet 的 Key 已经限定了一篇 Blog。每一个 member 是“点赞这篇 Blog 的用户”，score 是“这个用户点赞的时间”，不是该用户获得的点赞总数，也不是这篇 Blog 的点赞数。

Blog 的点赞总数保存在 `tb_blog.liked` 中，并且 `queryHotBlog` 使用数据库的 `orderByDesc("liked")` 查询热门 Blog。两者用途不同：

- `tb_blog.liked`：Blog 维度的点赞总数；
- `blog:liked:<blogId>`：用户维度的点赞记录和点赞时间。

## 4. `ORDER BY FIELD(id, ...)` 为什么能保持 Redis 顺序

### 4.1 当前代码中的实际用法

`queryBlogLikes` 中，Redis 返回用户 ID 后执行：

```java
List<Long> ids = top5.stream()
        .map(Long::valueOf)
        .collect(Collectors.toList());
String idStr = StrUtil.join(",", ids);
List<User> users = userService.query()
        .in("id", ids)
        .last("ORDER BY FIELD (id," + idStr + ")")
        .list();
```

关注收件箱的 `blogOfFollow` 也使用了同样的思路，只是它查询的是 Blog：

```java
String idStr = StrUtil.join(",", ids);
List<Blog> blogs = query()
        .in("id", ids)
        .last("ORDER BY FIELD (id," + idStr + ")")
        .list();
```

本篇的重点是点赞用户查询，但第二段代码说明了该项目已经在另一处真实使用相同的顺序保持方案。

### 4.2 `IN (...)` 做什么

假设 Redis 返回的用户 ID 顺序是：

```text
[5, 2, 9]
```

MyBatis-Plus 的：

```java
.in("id", ids)
```

会生成类似：

```sql
WHERE id IN (5, 2, 9)
```

`IN` 的作用是筛选：只返回 ID 属于 5、2、9 的记录。它表达的是“结果集合包含哪些行”，并不表达“结果行必须按照 5、2、9 的顺序返回”。关系型数据库在没有明确 `ORDER BY` 时不保证结果顺序。

所以不能因为 SQL 写成了 `IN (5, 2, 9)`，就认为结果一定是：

```text
5, 2, 9
```

数据库可能返回 `2, 5, 9`，也可能在不同执行计划、索引或数据状态下返回其他顺序。

### 4.3 `FIELD(id, 5, 2, 9)` 做什么

```sql
ORDER BY FIELD(id, 5, 2, 9)
```

可以理解为先计算一个排序值：

| id | `FIELD(id, 5, 2, 9)` 的结果 |
|---:|---:|
| 5 | 1 |
| 2 | 2 |
| 9 | 3 |

再按照这个计算出的值从小到大排序，于是结果顺序就是：

```text
5, 2, 9
```

因此：

- `IN (5, 2, 9)`：限定查询范围；
- `FIELD(id, 5, 2, 9)`：把指定 ID 列表中的位置转换成排序依据；
- 两者配合：既只查需要的记录，又尽量保持 Redis 给出的 ID 顺序。

如果只写：

```sql
ORDER BY id
```

得到的是按数值 ID 升序，例如 `2, 5, 9`，而不是 Redis 返回的业务顺序 `5, 2, 9`。所以在需要保持 Redis 顺序时，不能用 `ORDER BY id` 替代 `ORDER BY FIELD(id, ...)`。

### 4.4 如果数据库记录缺失，最终结果怎样

`IN` 和 `FIELD` 都不会制造不存在的用户或 Blog。假设 Redis 返回 `[5, 2, 9]`，但数据库只有 5 和 9：

- `IN (5, 2, 9)` 只查到 5、9；
- `FIELD` 仍按照它们在原列表中的位置排序；
- 最终结果是 `[5, 9]`；
- 缺失的 2 被省略，不会生成一个空用户对象，也不会自动补位。

这通常意味着 Redis 中存在历史 member，但对应数据库记录已被删除，或者两个存储已经出现不一致。当前代码没有针对缺失记录做回源、清理或补偿。

## 5. `ORDER BY FIELD` 与 Java Map 重排对比

### 方法一：数据库查询时使用 `ORDER BY FIELD`

执行思路：

1. Redis 返回有序 ID 列表，例如 `[5, 2, 9]`。
2. 用 `IN (5, 2, 9)` 一次性查询数据库。
3. 用 `ORDER BY FIELD(id, 5, 2, 9)` 让数据库直接按照输入列表排序。
4. 将查询结果转换成 DTO 返回。

优点：

- 数据库一次查询并完成排序，Java 代码较短；
- 适合当前这种“ID 数量较少、需要保持 Redis 顺序”的场景。

缺点：

- 需要拼接 `FIELD` 参数，SQL 可读性和数据库方言兼容性需要留意；
- ID 列表很大时，SQL 会变长；
- 仍然要处理数据库缺失记录，`FIELD` 不会自动补齐。

### 方法二：查询后使用 Java `Map` 重排

执行思路：

1. Redis 返回 `[5, 2, 9]`。
2. 使用 `IN` 查询数据库，不依赖数据库返回顺序。
3. 将查询结果转换为 `Map<Long, User>`，Key 是用户 ID。
4. 再遍历 Redis 返回的 ID 列表，按照 `[5, 2, 9]` 逐个从 Map 取 User。
5. 对取不到的 ID 选择跳过、记录日志或执行其他补偿处理。

优点：

- 不依赖 `FIELD`，数据库方言兼容性更好；
- 顺序控制明确，缺失数据如何处理也更容易在 Java 中表达；
- 适合需要更多业务组装逻辑的场景。

缺点：

- 需要额外的 Map 和结果列表；
- Java 代码更长；
- ID 列表很大时，应用层重排会增加内存和 CPU 开销。

当前项目实际使用的是**方法一**：`queryBlogLikes` 查询用户时使用 `.in("id", ids).last("ORDER BY FIELD ...")`；`blogOfFollow` 查询 Blog 时也使用了 `ORDER BY FIELD`。当前项目没有在这两处使用 Java `Map` 重排。Map 方案只能作为备选思路，不能写成已经实现。

## 6. 一个完整的小例子

下面是帮助理解的简化例子，不改变当前代码的实际描述。

### 第一步：Redis 保存点赞用户和时间

假设 Blog ID 为 100，Redis 中有：

```text
Key: blog:liked:100
member="5", score=100
member="2", score=200
member="9", score=300
```

member 是用户 ID，score 是点赞时间戳。这里为了演示使用较小数字，真实代码使用 `System.currentTimeMillis()`。

### 第二步：Redis 返回有序用户 ID

当前代码调用：

```java
range("blog:liked:100", 0, 4)
```

因此按 score 从小到大得到：

```text
["5", "2", "9"]
```

注意：由于实际代码使用 `range`，这个例子表示按较早点赞到较晚点赞的方向返回；如果业务需要最新点赞者在前，需要改用反向范围查询，但本轮没有修改代码。

### 第三步：根据 ID 查询数据库

代码将字符串转换为 Long，然后查询用户：

```sql
SELECT *
FROM tb_user
WHERE id IN (5, 2, 9)
ORDER BY FIELD(id, 5, 2, 9);
```

数据库负责补充用户的昵称、头像等完整信息，Redis 不保存这些展示字段。

### 第四步：保持原来的顺序

`IN` 只负责筛选，`FIELD` 负责把 ID 在 `[5, 2, 9]` 中的位置变成排序值。因此数据库结果保持为：

```text
User(5), User(2), User(9)
```

如果 User 2 在数据库中不存在，最终结果是 `User(5), User(9)`，不会凭空生成 User 2。

### 第五步：返回前端

`queryBlogLikes` 将 `User` 转换为 `UserDTO`，返回点赞用户列表。这个过程可以概括为：

```text
Redis 负责记录用户 ID 和排序依据
        ↓
Redis 返回有序 ID
        ↓
数据库根据 ID 查询完整用户信息
        ↓
ORDER BY FIELD 保持原有 ID 顺序
        ↓
User 转 UserDTO 返回前端
```

这只是对当前代码职责的概括：具体到点赞状态，代码还会直接用 Redis `score` 判断当前用户是否存在；具体到 Blog 详情和热门列表，代码还会从数据库查 Blog，再补充作者昵称、头像和 `isLike`。

## 7. 面试问题与参考答案

### 问题一：为什么点赞功能使用 ZSet？

参考答案：当前项目不仅要判断用户是否点过赞，还要读取点赞用户并按照点赞时间排序。ZSet 的 member 可以保存用户 ID，score 可以保存点赞时间戳，既能用 member 判断存在性，又能按 score 查询有序用户。普通 Set 只能很好地支持成员关系，不能直接按点赞时间排序。

### 问题二：ZSet 的 member 和 score 分别是什么？

参考答案：在 `blog:liked:<blogId>` 中，member 是点赞用户 ID 的字符串形式，score 是该用户点赞时的毫秒时间戳。它不是用户获得的点赞数量，也不是 Blog 的点赞总数。

### 问题三：为什么 `IN` 查询不能保证结果顺序？

参考答案：`IN` 只表示筛选出 ID 集合中的记录，例如 `id IN (5, 2, 9)`，不表示按参数书写顺序返回。没有显式 `ORDER BY` 时，数据库不保证结果顺序。

### 问题四：`ORDER BY FIELD(id, ...)` 的作用是什么？

参考答案：`FIELD` 返回某个 ID 在给定列表中的位置。`ORDER BY FIELD(id, 5, 2, 9)` 就会让 5、2、9 分别得到排序值 1、2、3，从而把数据库结果排列成 Redis 给出的顺序。

### 问题五：如果不使用 `FIELD`，还可以怎样保持 Redis 返回的 ID 顺序？

参考答案：可以先用 `IN` 查询数据库，再把结果转成 `Map<Long, User>`，最后遍历 Redis 返回的 ID 列表，按 ID 从 Map 中取出并重新组装列表。当前项目的点赞用户查询和关注收件箱查询没有采用这种方式。

## 8. 自测题

建议先遮住文末答案，独立说明代码的执行顺序。

1. Redis 中 `blog:liked:100` 有 `member=5, score=100` 和 `member=2, score=200`。当前登录用户 ID 为 5，再调用 `PUT /blog/like/100` 会走点赞还是取消点赞分支？会执行哪些数据库和 Redis 操作？
2. Redis 返回用户 ID `[5, 2, 9]`，但 `tb_user` 中只有 5 和 9。使用 `IN` 加 `ORDER BY FIELD` 后，最终列表包含哪些用户？为什么？
3. 当前 `queryBlogLikes` 使用 `range(key, 0, 4)`，score 是点赞时间戳。如果希望“最新点赞用户排在前面”，仅仅把变量名 `top5` 改掉是否足够？需要关注哪一个 Redis 查询方向？

## 9. 今日难点与后续建议

### 9.1 目前的理解情况

通过对照 `BlogController`、`IBlogService` 和 `BlogServiceImpl`，现在可以把 ZSet 的三个关键角色对应到真实代码：

- member 是用户 ID，用于记录谁点过赞；
- score 是点赞时间戳，用于提供点赞用户的顺序依据；
- `score(key, member)` 用于点赞状态判断，`range` 用于读取有序 member。

但仍需要注意：当前代码把“点赞状态判断”和“点赞用户列表排序”放在同一个 ZSet 中完成，容易在理解时误认为这是按点赞数量排名的排行榜。实际它是某篇 Blog 的点赞用户记录，当前查询方向还是 score 升序。

### 9.2 `ORDER BY FIELD` 解决了什么问题

它解决的是：Redis 已经给出了一组有业务意义的 ID 顺序，但数据库的 `IN` 查询不会自动保留这个顺序。`FIELD` 把输入 ID 列表中的位置转换为 SQL 排序依据，让查询结果与 Redis 顺序对齐。

它没有解决：

- MySQL 与 Redis 的一致性；
- 数据库缺失记录的补齐；
- 大 ID 列表导致 SQL 过长；
- 点赞操作的跨存储原子性。

### 9.3 后续建议

- 通过 Redis 实际执行一次 `ZADD`、`ZSCORE`、`ZRANGE`、`ZREMRANGEBYRANK` 或相关查询，确认 score 升序和反向查询的差别。
- 用两组数据验证：一组数据库返回完整，一组故意删除一个 Redis member 对应的用户，观察最终结果如何缩短。
- 分别实现一次 `ORDER BY FIELD` 和 Java `Map` 重排的小实验，比较缺失记录处理和代码复杂度。
- 如果后续要增强代码，再单独讨论点赞操作的事务、一致性、并发重复点赞和点赞数不能小于 0 等问题；这些不属于本轮文档整理时擅自修改的内容。

## 10. 验证情况

- 已完成静态代码阅读，并对照工作区 `git diff` 核对 Controller、Service 接口、Service 实现、实体字段和 Redis Key。
- 已执行 `git diff --check`，未发现空白错误。
- 已执行 `git show --stat HEAD` 和 `git show --name-status HEAD`，确认最近一次提交 `d3f0bf7` 实际包含 `DAY-07.md`、关注 Controller、关注 Service 接口和关注 Service 实现，没有包含本次达人探店工作区改动。
- 已尝试执行 `mvn test`，但未进入测试阶段：当前环境的 `JAVA_HOME` 未正确设置，Maven 报错 `The JAVA_HOME environment variable is not defined correctly`。
- 因此 Maven 测试、编译、应用启动、Redis/MySQL 连接和接口端到端验证均为**未验证**。

### 自测题参考答案

1. 当前用户 ID 5 已经是 `blog:liked:100` 的 member，`score(...)` 不为 `null`，所以会进入取消点赞分支。先执行数据库 `liked = liked - 1`；数据库更新成功后执行 ZSet `remove(key, "5")`。当前代码没有跨数据库和 Redis 的事务保证。
2. `IN (5, 2, 9)` 只会查询到 5 和 9；`FIELD` 按原列表位置排序，因此最终顺序是 `[5, 9]`。不存在的 2 会被省略，不会自动生成空记录或占位对象。
3. 仅改变量名不够。`range` 按 score 从小到大返回，若要把较晚点赞的用户排在前面，需要使用反向范围查询，例如与 `reverseRange` 对应的 API；本轮实际代码没有做这个修改。
