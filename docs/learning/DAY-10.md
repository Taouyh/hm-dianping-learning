# DAY-10 学习总结：附近商铺与 Redis GEO 分页

> 本文基于当前仓库代码和本次工作区差异整理。附近查询主路径在 `ShopServiceImpl.queryShopByType`；分页部分是已阅读代码，不代表已经能够独立实现。

## 一、功能目标与调用链

附近商铺解决的是：用户浏览某类商铺时，给出当前位置后优先看到附近商铺，并显示距离；没有位置时，仍可按类型分页浏览。

```text
GET /shop/of/type?typeId=...?current=...?x=...?y=...（x/y 可选）
  -> ShopController.queryShopByType
  -> IShopService.queryShopByType
  -> ShopServiceImpl.queryShopByType
      -> 无完整坐标：MySQL 按 type_id 分页
      -> 有坐标：Redis GEO 搜索 ID/距离 -> MySQL 按 ID 取商铺并恢复顺序
  -> Result.ok(List<Shop>)
```

- `ShopController` 绑定 HTTP 参数并委托服务层。
- `IShopService` 声明用例入口；`ShopServiceImpl` 实现分支、GEO 查询、数据库补全和结果封装。
- `ShopMapper` 继承 `BaseMapper<Shop>`；当前逻辑通过 MyBatis-Plus Service 的 `query()`/`in()` 查询，并没有直接调用自定义 Mapper SQL。
- `Shop` 的 `x`、`y` 是坐标字段，`distance` 标记为 `@TableField(exist = false)`，只用于返回结果，不映射数据库列。
- `Result.ok(...)` 包装列表；当前两条路径均返回商铺列表，而不是带 total 的分页对象。

## 二、当前实现细节

### 接口与数据来源

`GET /shop/of/type` 的参数：`typeId` 必传，`current` 默认 `1`，`x`、`y` 可选。方法返回 `Result`，其 data 是 `List<Shop>`。

- 没有同时提供 `x`、`y`（当前条件是 `x == null || y == null`）：查 MySQL，`eq("type_id", typeId)`，使用 `Page<>(current, DEFAULT_PAGE_SIZE)`，默认每页 5 条。缺省坐标只有一项时，当前实现也会回退到这条普通分页路径。
- 同时提供坐标：以 `SHOP_GEO_KEY + typeId` 作为 Redis key，即 `shop:geo:<typeId>`，搜索半径参数为 `new Distance(5000)`，请求距离字段并显式 `.sortAscending()`（由近到远），再从 MySQL 补齐商铺详情。
- `HmDianPingApplicationTests.loadShopData()` 是手动运行的测试方法：读数据库商铺，按 `Shop.typeId` 分组，以商铺 ID 字符串作为 GEO member，以 `Point(shop.getX(), shop.getY())` 写入 `shop:geo:<typeId>`。这不是应用启动时自动同步。

### GEO 距离与顺序

Redis GEO 保存坐标与 member（这里是商铺 ID），查询中心由 `GeoReference.fromCoordinate(x, y)` 给出。`.includeDistance()` 使结果包含距离，`.sortAscending()` 请求按距离升序排列。代码先从 GEO 取得有序 ID 和距离；数据库 `IN` 查询本身不保证顺序，所以 SQL 使用 `ORDER BY FIELD(id,...)` 按 GEO ID 顺序恢复结果。随后把距离写到每个 `Shop.distance`。

当前 `ShopServiceImpl.update` 更新数据库并删除普通店铺缓存，但没有同步更新/删除 GEO member。因此修改店铺坐标或类型后，附近索引可能陈旧；项目目前没有在此处实现 GEO 一致性维护。

## 三、分页查询：逐步读代码（重点复习）

附近分支没有把 Redis GEO 当成传统的“任意页数据库分页”。代码按当前页计算：

```text
from = (current - 1) * DEFAULT_PAGE_SIZE
end  = current * DEFAULT_PAGE_SIZE
```

`DEFAULT_PAGE_SIZE` 当前是 5。接下来 GEO `search` 请求距离、升序，并设置 `.limit(end)`：Redis 从满足半径条件的结果中，按从近到远最多给出前 `end` 个。Java 再通过 `.skip(from)` 跳过之前页面的项。该代码将 `new Distance(5000)` 作为半径参数传给 Spring Data Redis；距离 API 的实际单位/换算应通过当前依赖版本或集成测试确认，文档不据此断言 `Shop.distance` 的单位。由于查询没有 offset 参数，这种方式是“取前 N 条，再在应用层跳过前面的条目”。

| 参数 | 当前代码中的作用 | 示例（每页 5 条） |
| --- | --- | --- |
| `current` | 页码，从 1 开始；参与计算 from/end | 1 或 2 |
| `from` | 要跳过的 GEO 结果数，不是数据库 ID 起点 | 第 1 页为 0，第 2 页为 5 |
| `end` | GEO 本次最多取到的排名位置/条数上限 | 第 1 页为 5，第 2 页为 10 |
| `limit(end)` | 只取距离排序后的前 end 个结果 | 页 2 仍要先拿前 10 个 |
| `skip(from)` | 丢掉前 from 个，再处理当前页候选 | 页 2 跳过前 5 个 |

**手动推演：**假设半径内按距离升序的 ID 为 `A…L`。

- 第 1 页：`current=1`，`from=0`、`end=5`。GEO 最多返回 `A…E`，跳过 0 个，数据库按该顺序取回 `A…E` 并附距离。
- 第 2 页：`current=2`，`from=5`、`end=10`。GEO 最多返回 `A…J`，跳过前 5 个，取 `F…J`，再按其 GEO 顺序从数据库取回并附距离。
- 如果 GEO 结果数 `<= from`，代码直接返回空列表，表示当前页没有候选项。若结果不足 `end`，`skip(from)` 后自然只剩余实际存在的条目。

与普通查询的差别：无坐标分支由 MyBatis-Plus `Page` 分页数据库记录；附近分支先由 Redis 按位置和距离筛选/排序 ID，再通过数据库补详情，当前页是在 GEO 有序结果上通过 `limit(end)` + Java `skip(from)` 得到。页数越靠后，重复读取的前序 GEO 结果越多；这段代码也没有返回总页数/总条数。

易混点：

1. `from` 是结果列表里的零基跳过数量，不是 SQL 的 `OFFSET`，也不是商铺 ID。
2. `end` 在本实现里既作为 GEO 的最大返回数量，也对应当前页的结束边界；不是本页条数。本页条数仍最多为 `DEFAULT_PAGE_SIZE`。
3. 距离排序要在 GEO 查询中明确请求 `.sortAscending()`；`ORDER BY FIELD` 只保留传入 ID 的顺序，本身不计算或比较距离。
4. 数据库 `IN` 查询可能打乱顺序，因此需要恢复 GEO 顺序；否则展示顺序与距离映射可能不符合预期。
5. 坐标需成对传入才进入附近分支；当前代码没有对页码、经纬度范围进行额外校验。

## 四、学习状态

### 已完成的功能

- 当前工作区增加了带可选坐标的商铺类型查询入口和服务实现。
- 附近分支按类型读取 GEO 索引、半径查询、请求距离升序、读取当前页候选 ID/距离，再查数据库并填充 `Shop.distance`。
- 测试类中有手动将数据库商铺分组写入 GEO 的 `loadShopData()`。

### 已经阅读、但尚未完全理解的代码

- 用户明确说明：GEO 分页查询代码是直接复制后阅读过解析，不能记作独立实现或完全掌握。
- 尤其要能脱离注释解释 `from/end/limit/skip` 的关系、GEO 返回结构、距离对象和 `ORDER BY FIELD` 的必要性。

### 需要后续复习和验证的知识点

- 用真实 Redis 数据验证一页/两页的 ID、距离、顺序和页边界；确认当前依赖版本下距离数值的单位与序列化表现。
- 验证空 GEO key、无匹配商铺、坐标只传一项、页码超出结果等边界。
- 研究商铺新增/改类型/改坐标/删除时如何维护 GEO 索引；当前代码尚未实现这套同步。
- 研究后续页重复读取前序结果的成本，以及更大数据量下的分页方案和索引维护策略。

## 五、学习复盘与面试题

### 关键易错点

- 经度传 `x`、纬度传 `y`，写入与查询必须一致。
- GEO member 是字符串商铺 ID，不是完整商铺对象；详情仍从 MySQL 补齐。
- 必须区分 Redis 的距离顺序、数据库 `IN` 结果顺序和最终返回顺序。
- 普通分页与附近分页是两条不同查询路径；不要声称附近结果由 MyBatis-Plus Page 完成。
- 当前 GEO 装载依赖手动测试方法，更新同步没有实现，不能说数据会自动保持一致。

### 面试问答思路

**1. 附近商铺功能如何实现？** 说明接口接收类型、页码和可选坐标；有坐标时按类型访问 `shop:geo:<typeId>`，以当前位置和半径搜索、按距离升序取 ID/距离，再按 ID 从 MySQL 查详情、恢复 GEO 顺序、填充距离；无坐标时走 MyBatis-Plus 普通分页。指出 GEO 数据当前由测试方法手动导入。

**2. 为什么用 Redis GEO，而不是直接遍历数据库商铺？** GEO 能对坐标做范围检索并返回距离，避免应用逐条加载大量商铺后计算距离和排序。回答时也要说明：本项目仍访问 MySQL 获取详情；GEO 索引需要导入并维护，当前同步能力有限。

**3. GEO 查询结果如何进行距离排序？** 当前查询显式 `.sortAscending()` 并 `.includeDistance()`；拿到有序 member 和距离后，SQL `ORDER BY FIELD` 保持该顺序，最后写入 `Shop.distance`。数据库排序不是距离计算来源。

**4. 分页的偏移量和每页条数各有什么作用？** 每页条数是 5；`from=(current-1)*5` 是应跳过的前序结果数，`end=current*5` 是累计取数上限。比如第二页取前 10 个 GEO 结果再跳过 5 个，得到第 6～10 个。它不是直接给 Redis 的 offset 分页。

**5. 商铺更新后 Redis 如何保持一致？当前项目做到哪一步？** 理想上在商铺创建、删除、类型或坐标变更时同步更新旧/新类型 GEO member，并考虑失败重试、事务边界或定期重建。当前 `update` 只更新 MySQL 并删除普通详情缓存，没有维护 GEO，所以可能陈旧；`loadShopData()` 是手动导入测试，不是自动同步。

## 六、后续学习任务（每项有完成标准）

1. **画流程图：**不看本文画出 HTTP → Controller → Service → Redis GEO → MySQL → Result；完成标准是标清无坐标/有坐标分支、ID/距离在哪一步产生。
2. **参数口述：**不看本文解释 `current/from/end/limit/skip/DEFAULT_PAGE_SIZE`；完成标准是准确说出页 1 和页 2 的数值，并指出哪个是累计上限、哪个是跳过数。
3. **手推两页：**给定至少 12 个按距离排序的 member，手算页 1、页 2 的 Redis 返回集合、skip 后集合和最终顺序；完成标准是结果正确且解释为什么 SQL 要恢复顺序。
4. **改参验证：**在本地测试中改变页大小或 `current`，记录 GEO 返回数和接口 ID/距离；完成标准是确认无坐标分页与附近分页分别受哪些参数影响，并恢复代码到预期值。
5. **独立复述：**不打开文档，用 3 分钟讲完整查询并回答五道面试题；完成标准是明确区分已实现、未实现和自己仍不确定的点，不把复制阅读说成独立实现。
