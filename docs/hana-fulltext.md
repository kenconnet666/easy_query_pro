# HANA 全文查询

HANA 全文属于查询级增强。`HanaSearch.from` 在同一物理表 SELECT 中安装 CONTAINS 条件和 SCORE 投影，再把完整结果交给原 EntityQueryable。应用可以继续筛选分数、排序、与普通来源 JOIN、分组、CTE、count 和分页；没有另一套搜索执行器，也没有能脱离查询使用的裸 SCORE 函数。

## 入口与结果映射

```java
@EntityProxy
public class SearchHit extends Document {
    @Column("SEARCH_SCORE")
    private Double score;

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}

var documents = query.queryable(DocumentProxy.createTable())
        .asSchema(ignored -> "CONTENT")
        .where(document -> document.enabled().eq(true));

var search = HanaSearch.from(documents,
        document -> HanaSearchSpec.fuzzy("coffee", 0.7, document.title()));
var page = search.query(SearchHitProxy::createTable)
        .where(hit -> hit.score().ge(0.1))
        .orderBy(hit -> {
            hit.score().desc();
            hit.id().asc();
        })
        .toPageResult(1, 20);
```

client 使用 `HanaDatabaseConfiguration`，并在构建前安装 `HanaExtensions::configure`。`Document` 使用普通 String 字段，输出 DTO 的其他列按原物理列名映射；`SEARCH_SCORE` 必须对应 Double 属性。来源本身不能已有同名列。输出 Proxy 工厂每次返回新的未绑定实例。

来源保存构造时的查询快照。后续修改 `documents` 不会修改已经定义的搜索，重复调用 `query` 可生成独立结果来源。源查询的临时读取器不会污染同名输出属性；输出 DTO 以自己的字段映射和 handler 为准。查询仍使用原 client 的事务，测试已确认同步索引可在同一事务中搜索新增行，回滚后不再命中。

## 精确、多列与组合搜索

```java
var exact = HanaSearch.from(documents,
        document -> HanaSearchSpec.exact("coffee", document.title()));

var titleOrBody = HanaSearch.from(documents,
        document -> HanaSearchSpec.exact("Arabica", document.title(), document.body()));

var combined = HanaSearch.from(documents,
        document -> HanaSearchSpec.exact("coffee", document.title())
                .and(HanaSearchSpec.exact("Colombia", document.body())));
```

同一个多列 CONTAINS 在所选列中搜索。多个 spec 的 `and` 则产生多个 AND 组合的 CONTAINS，最多允许其中一个为多列条件；每个条件不能重复同一列。这里不支持 `*` 自动扩展为所有列。

搜索文本是 HANA freestyle 查询文本，可以包含 OR、短语引号等搜索语法；JDBC 绑定保持 SQL 参数边界，但不会把搜索语法改成普通文字。与现有 `String.contains` 的 LIKE 语义不同。空白、NULL、NUL 和未配对代理项在 Java 边界拒绝；数据库列的 NULL 仍遵循原生不匹配规则。

FUZZY 阈值必须有限并位于 0–1。搜索文本和阈值都作为 JDBC 参数传入，当前 HANA 2.00.088 与 ngdbc 已实际执行 `FUZZY(?)`。阈值是词匹配容差，最终 SCORE 还取决于全文评分算法；不能要求每个命中 SCORE 都大于阈值，也不能把 FUZZY(0) 当作“不过滤”。

SCORE 是本次所有搜索条件的综合分数，不是单个条件的独立分数。它不能在不同数据库之间比较，也不保证跨 HANA 版本完全相同；测试比较同一数据、同一版本的 DSL 和原生 JDBC 结果。需要稳定分页时，在分数之后追加业务唯一键排序。

## 真实索引前提

索引由应用迁移或 DBA 管理，搜索 API 不隐式创建索引：

```sql
CREATE COLUMN TABLE "CONTENT"."DOCUMENT" (
    "id" INTEGER PRIMARY KEY,
    "title" NVARCHAR(200),
    "body" NVARCHAR(1000)
);
CREATE FULLTEXT INDEX "CONTENT"."TITLE_FT"
    ON "CONTENT"."DOCUMENT" ("title")
    FUZZY SEARCH INDEX ON SYNC FAST PREPROCESS ON TEXT ANALYSIS OFF;
CREATE FULLTEXT INDEX "CONTENT"."BODY_FT"
    ON "CONTENT"."DOCUMENT" ("body")
    FUZZY SEARCH INDEX ON SYNC FAST PREPROCESS ON TEXT ANALYSIS OFF;
```

入口固定使用 `FULLTEXT(ON)`。没有适用全文索引时数据库报错，不退为 LIKE，也不静默使用无全文索引的字符串比较。测试以同步索引避免异步预处理队列等待；生产系统可自行选择索引维护方式及其可见性策略。

这套模式支持 EXACT/FUZZY。FAST PREPROCESS ON 不支持 LINGUISTIC，本入口也没有把语言、分词、文本分析、模糊比较高级选项包装成默认跨库行为。列是否为支持的实际列存表、索引及权限是否可用，由部署与数据库校验；Java 的来源检查不替代目录或权限检查。

## 查询边界

- 输入须为单表映射查询，搜索列必须属于当前来源且为普通 String 映射列；函数、CAST、SQL 属性转换列和外来列拒绝。
- 不能把已投影、分组、DISTINCT、派生表或 CTE 查询作为全文输入。上游 CTE 使用普通表 builder，因此还通过公开 `CteTableAvailable` 识别。
- 原查询已有 WHERE、逻辑删除、schema、排序和 limit 保持原查询语义；完整原 WHERE 先整体包组，再 AND 搜索条件，避免根级 OR 分支绕过搜索。搜索条件与 SCORE 都留在物理列所在 SELECT。
- 后续展示过滤位于搜索结果之外，SMART 不能把它移到无对应 CONTAINS 的评分层。CTE、clone 与后续 JOIN 保留这一边界。
- 未证明分片评分可合并，构造时或后续组合引入分片都拒绝。分数排序也不能用普通分片 SUM/AVG 合并规则代替。
- 计算视图、表函数、临时表和 SQLScript 表变量不自动列为本入口支持的全文来源。带索引的物理表是当前验收范围。

HANA 2.00.088 不支持在同一个 SQL 中 JOIN 两个分别计算 SCORE() 的子查询，实际返回错误码 7，提示仅允许 UNION/UNION ALL。该入口保留这个原生错误，不偷偷改成另一种评分或在 Java 中合并搜索结果。两个评分来源的 UNION ALL、其外层排序和 count 已通过实库验证；同一物理表需要组合条件时优先在一个 `HanaSearchSpec` 内使用 `and`，得到一份综合分数。

EasyQuery 3.2.17 的实体 JOIN 还有一个与全文无关的边界：右侧 queryable 仅有 `asSchema/asTable` 而没有投影、筛选等操作时，上游会把它折叠成直接实体 JOIN，丢失这些动态来源设置。需要保留右侧来源时使用真实投影：

```java
var right = query.queryable(DocumentProxy.createTable())
        .asSchema(ignored -> "CONTENT")
        .select(document -> document);
var joined = search.query(SearchHitProxy::createTable)
        .leftJoin(right, (hit, document) -> hit.id().eq(document.id()));
```

本包没有宣称修复该上游 JOIN 优化，也没有添加伪 WHERE 掩盖它。后续上游公开挂载点或版本变化需单独验收。

## 验证与依据

`HanaSearchDslTest` 和消费者编译用例覆盖类型边界、参数顺序、来源快照、不可变组合、错库、CTE/派生来源、输出 Proxy 生命周期和晚追加分片。`HanaSearchIT` 创建专用 schema、真实列存表和两个同步全文索引，检查目录并与原生 JDBC 比较 EXACT/FUZZY、多列和组合评分，覆盖引号/中文/原生 OR/短语、NULL、未命中、阈值边界、根级 OR 筛选、分页/CTE/SMART/普通来源 JOIN 与 UNION ALL，同时验证读取器隔离和事务回滚。schema 及索引随测试清理，结果证据见 [执行记录](execution.md)。

官方语义依据：[CONTAINS](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/20f952437519101487edc3d9aba84238.html)、[SCORE](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/20e6f8e97519101489a0cd3c29991e72.html)、[CREATE FULLTEXT INDEX](https://help.sap.com/docs/SAP_HANA_PLATFORM/4fe29514fd584807ac9f2a04f6754767/20d4117e75191014ba5aaab91b3f087d.html)、[HANA 2.0 SPS 08 Search Developer Guide](https://help.sap.com/doc/95545fb91e744a87a43fae1ca08a61cf/2.0.08/en-US/SAP_HANA_Search_Developer_Guide_en.pdf)。HANA Cloud 与其他 revision 未由当前本地证据自动覆盖。
