# DAY-02 学习复盘

## 1. 今日目标

完成黑马点评用户短信登录的 Redis 版本，并理解 Token、Redis Hash、刷新 Token 拦截器和登录校验拦截器的协作方式。

## 2. 今日完成

- 将发送验证码逻辑从 Session 思路改为 Redis 保存。
- 校验手机号并生成 6 位验证码。
- 使用 `login:code:<phone>` 作为 Redis key，验证码作为 value，并设置 2 分钟过期时间。
- 使用手机号和验证码完成登录校验。
- 用户不存在时，根据手机号创建用户并保存到 MySQL。
- 将 `UserDTO` 转换为 Map，以 Redis Hash 形式保存登录用户信息。
- 生成 Token，将 Token 返回给前端。
- 增加刷新 Token 拦截器和登录校验拦截器。
- 在 `/user/me` 中通过 `UserHolder` 获取当前登录用户。

## 3. 功能实现

### 发送验证码

`UserController` 接收手机号后调用 `UserServiceImpl.sendCode`。服务层完成手机号校验、验证码生成和 Redis 保存：

```text
login:code:<phone> -> 6 位验证码，TTL 2 分钟
```

验证码通过日志输出，当前没有接入真实短信平台。

### 短信登录

登录时从请求体获取手机号和验证码，根据手机号读取 Redis 验证码并比较。验证通过后根据手机号查询用户；如果用户不存在则创建用户。随后生成随机 Token，将脱敏后的 `UserDTO` 保存为 Redis Hash，并返回 Token。

### 拦截器

- `RefreshInterceptor` 拦截所有请求，读取 `authorization` 请求头中的 Token。
- 如果 Token 对应的 Redis Hash 存在，则转换为 `UserDTO`，保存到 `UserHolder`，并刷新 Redis TTL。
- `LoginInterceptor` 负责判断需要登录的请求是否已经有当前用户。
- `MvcConfig` 中将刷新拦截器设置为 `order(0)`，登录拦截器设置为 `order(1)`，保证先读取用户再进行登录校验。
- `RefreshInterceptor.afterCompletion` 中清理 `UserHolder` 的 `ThreadLocal`。

## 4. 涉及的技术

- Spring Boot Controller、Service 分层和依赖注入。
- Spring MVC `HandlerInterceptor`、`WebMvcConfigurer` 和拦截器执行顺序。
- Spring Data Redis `StringRedisTemplate`。
- Redis String 保存验证码、Redis Hash 保存用户信息、TTL 过期时间。
- Hutool `RandomUtil`、`BeanUtil` 和 `CopyOptions`。
- MyBatis-Plus `query()`、`one()`、`save()`。
- `ThreadLocal` 保存当前请求线程的用户信息。
- Docker Desktop 中 Redis 的启动、端口映射和逻辑数据库使用。

## 5. 今天遇到的问题

### 问题 1：Docker Redis 无法连接

- 问题：PowerShell 执行 `docker pull` 时提示 `dockerDesktopLinuxEngine` 不存在。
- 原因：Docker Desktop 或 Linux 容器引擎没有运行。
- 解决过程：确认需要先启动 Docker Desktop，检查 Docker Server、容器状态和端口映射；使用已有 Redis 容器，不重复执行 `docker run`。
- 最终解决方案：复用 `projectlearn-ai` 创建的 Redis，通过宿主机 `127.0.0.1:6379` 访问。
- 我是否真正理解：🟡 理解后完成。已经理解 Docker CLI、Docker Engine、容器和宿主机端口之间的关系，但仍需要继续积累环境排障经验。

### 问题 2：Redis 配置与项目隔离

- 问题：当前 Redis 无密码、默认 16 个逻辑库、AOF 关闭，与课程示例不完全相同。
- 原因：Docker Redis 使用的是已有容器配置，课程 CentOS 配置只是另一种部署方式。
- 解决过程：确认黑马点评可以使用 `db1`，`projectlearn-ai` 使用 `db0`，并在 IDEA 的 Redis 配置中指定数据库编号。
- 最终解决方案：两个项目共用 Redis 服务，通过逻辑库和 key 前缀隔离数据。
- 我是否真正理解：🟡 理解后完成。理解了逻辑库隔离和 `FLUSHALL`/`FLUSHDB` 的风险，但还没有完成两个项目同时运行时的完整验证。

### 问题 3：Session 登录后访问 `/user/me` 又跳回登录页

- 问题：登录成功后，点击“我的”仍被认为未登录。
- 原因：前端最终流程使用 Token，而后端最初尝试使用 Session；同时 `/user/me` 依赖 `UserHolder`，但拦截器配置和实现尚未完成。
- 解决过程：分析前端请求头中的 Token，理解登录响应必须返回 Token，刷新拦截器需要先从 Redis 读取用户并保存到 `UserHolder`。
- 最终解决方案：改为 Redis 保存登录用户和 Token，使用刷新拦截器和登录校验拦截器协作。
- 我是否真正理解：🟡 理解后完成。已经理解 Session 方案和 Redis Token 方案的区别，以及前端请求头 Token 的作用；Token 不一致问题还需要继续通过浏览器 Network 和 Redis 实际数据验证。

### 问题 4：`MvcConfig` 中拦截器没有生效

- 问题：拦截器注册方法写成了 `addInterceptor`。
- 原因：Spring MVC 要求配置类实现 `WebMvcConfigurer`，并覆写 `addInterceptors` 方法。
- 解决过程：检查 Spring MVC 配置接口和方法签名。
- 最终解决方案：让 `MvcConfig` 实现 `WebMvcConfigurer`，使用 `@Override` 和 `addInterceptors`。
- 我是否真正理解：🟡 理解后完成。

### 问题 5：Redis Hash 空 Map 判断

- 问题：用 `userMap == null` 判断 Token 是否存在。
- 原因：`opsForHash().entries()` 通常返回空 Map，而不是 `null`。
- 最终解决方案：使用 `userMap.isEmpty()` 判断 Redis 中是否没有用户信息。
- 我是否真正理解：🟡 理解后完成。

## 6. 今日踩坑

- 在 Redis-cli 中输入 `redis-server` 是错误用法；它是启动程序，不是 Redis 命令。
- Token 方案中，登录响应 Token、前端保存的 Token、请求头 Token 和 Redis key 必须一致。
- `RefreshInterceptor` 只负责读取已有 Token、刷新 TTL 和保存 `UserHolder`，首次保存 Token 应在登录逻辑中完成。
- `LOGIN_USER_TTL = 36000` 按课程语义应使用 `TimeUnit.SECONDS`，不能在登录和刷新时混用分钟。
- `UserHolder` 使用 `ThreadLocal`，请求结束后必须清理，避免线程复用造成用户信息串线。
- Redis 共用时不能随意执行 `FLUSHALL`，否则会清空其他项目的数据。

## 7. 我的思考

- 手机号是查询验证码和用户的稳定标识，验证码不是全局唯一标识，不能用验证码反查手机号。
- DTO 只保存当前登录所需字段，可以避免把用户密码等敏感信息放入 Session 或 Redis。
- 拦截器的职责是处理所有请求共有的登录状态逻辑，Controller 只通过 `UserHolder` 获取当前用户。
- Redis 逻辑库只能提供基础隔离，项目级 key 前缀仍然很重要。

## 8. 我的创新点

今日暂无独立创新点。

## 9. 教程代码 vs 我的代码

- 用户短信登录、Redis 验证码、Token 登录、Redis Hash、双拦截器和 `UserHolder` 的整体设计参考黑马教程完成，属于：🟠 参考教程完成 / 待掌握。
- 今天对 Session、Redis Token、Docker Redis、拦截器顺序和 `UserDTO` 的理解经过问题排查得到，属于：🟡 理解后完成。
- 当前没有可以确认属于独立设计或独立功能扩展的实现。

## 10. 面试知识点

- `@RequestParam` 与 `@RequestBody` 的区别。
- Spring MVC 拦截器的注册方式和 `order` 执行顺序。
- Token 登录相较于 Session 登录的基本流程。
- Redis String、Hash 和 TTL 的使用场景。
- 为什么验证码使用“手机号 -> 验证码”的 Redis key 结构。
- 为什么登录用户信息应保存为脱敏后的 DTO。
- `ThreadLocal` 的用途及请求结束清理原因。
- MyBatis-Plus `query().eq(...).one()` 和 `save()` 的作用。
- Docker 容器端口映射与 Redis 逻辑库隔离。

## 11. 今日掌握程度

### 🟢 独立完成

今天没有足够证据确认完整功能由我独立完成。

### 🟡 理解后完成

- 理解短信登录从请求到 Redis、MySQL、Token 返回的调用链。
- 理解两个拦截器的职责和执行顺序。
- 理解 DTO、Redis Hash 和 `UserHolder` 的关系。
- 理解 Docker Redis 的基本启动和连接排障过程。

### 🟠 参考教程完成 / 待掌握

- Redis 短信登录和 Token 登录的完整代码实现。
- 登录校验拦截器和刷新 Token 拦截器的完整实现。
- 前后端 Token 一致性问题的端到端验证。

### 🔴 目前不会

- 尚未独立完成登出、Token 删除和完整异常处理。
- 尚未完成完整自动化测试和多项目同时使用 Redis 的验证。

## 12. 明日建议

1. 重新验证登录响应中的 Token 与 `/user/me` 请求头是否一致。
2. 在 Redis `db1` 中检查 `login:code:*` 和 `login:token:*` 的内容及 TTL。
3. 完成并验证登出功能，理解如何删除 Redis Token。
4. 清理无用 import 和参数，但不要进行与学习目标无关的重构。
5. 为已完成的登录流程做一次小范围手动测试，再开始下一个功能。

## 13. Git Commit

当前 HEAD 仍为官方初始基线：

```text
ab14ecb chore: import original Heima Dianping project
```

今天的业务修改尚未提交。建议在确认代码可以编译并完成必要手动验证后，提交今天的 6 个 Java 文件和本复盘文档。`README.md` 是前一天新增的项目说明文档，建议不要与今天的业务功能混在同一个 commit 中。
