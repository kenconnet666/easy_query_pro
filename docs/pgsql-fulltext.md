# PostgreSQL 全文查询

`PgSearch` 为一个已经映射的 `tsvector` 列构造匹配与评分，再返回普通 `EntityQueryable`。过滤、排序、CTE、JOIN、UNION、分页和事务继续使用 EasyQuery 3.2.17 的 API。全文编码类型见 [tsvector/tsquery 值类型](pgsql-text-search-types.md)。

## 安装与存储

在构造 client 时安装基础扩展与显式查询作用域，构造完成后、首次加载实体元数据前安装类型处理器：

```java
.customConfigure(services -> {
    PgSqlExtensions.configure(services);
    PgSqlExtensions.configureQueryScope(services);
})
```

```java
PgSqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
```

普通 `configure` 保留之前注册的 `ExpressionFactory`。应用需要在上述安装之后替换工厂时，可以继承 `PgSqlProExpressionFactory`，或者在自定义 SELECT 表达式中保留 `ProQuerySQLContext.withQueryContext`。全文入口在渲染时校验根上下文，不会将缺少传播标记当成没有分片。

实体将检索列声明为 `PgTsVector`，例如 `@Column(dbType = "tsvector") private PgTsVector document;`。推荐用明确配置的 stored generated column，并创建 GIN：

```sql
CREATE TABLE article (
    id integer PRIMARY KEY,
    title text,
    body text,
    document tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('pg_catalog.english', coalesce(title, '')), 'A')
        || setweight(to_tsvector('pg_catalog.english', coalesce(body, '')), 'D')
    ) STORED
);
CREATE INDEX article_document_idx ON article USING GIN (document);
```

示例使用 `coalesce`，所以全部原文为 NULL 时得到空 vector。业务若需要保留 SQL NULL，可在生成表达式中使用 CASE；入口保留这两者的区别。生成列不应由实体插入/更新直接赋值，应写入原文字段；生成表达式与索引由数据库维护。建表配置依据 [PostgreSQL 17 全文表与索引](https://www.postgresql.org/docs/17/textsearch-tables.html)。

检索配置必须与生成 vector 时一致。已经按 English 词干规则保存的 vector，使用 Simple 查询配置不会恢复原词；配置名作为 `regconfig` 参数绑定，存在性、权限与词典规则由 PostgreSQL 校验。建议使用完整配置名，避免依赖 `search_path`。本库不部署词典、不自动选择中文分词器，也不推断已有列采用哪种语言。

## 输入与查询组合

输出 DTO 用 `@Column("SEARCH_SCORE") Double score` 保存分数，其他字段按正常实体映射声明，并使用 APT 生成新的输出 Proxy：

```java
var search = PgSearch.from(
    entityQuery.queryable(ArticleProxy.createTable()).where(row -> row.published().eq(true)),
    row -> PgSearchSpec.web("pg_catalog.english", "\"coffee beans\" -tea", row.document())
        .rankedBy(PgSearchSpec.Ranking.COVER_DENSITY,
                  PgSearchSpec.Normalization.DOCUMENT_LENGTH,
                  PgSearchSpec.Normalization.RANK_OVER_RANK_PLUS_ONE));

var page = search.query(ArticleHitProxy::createTable)
    .orderBy(row -> {
        row.score().desc();
        row.id().asc();
    })
    .toPageResult(1, 20);
```

每次 `query` 都要求新的未绑定 Proxy。来源在 `from` 时只克隆 ClientQueryable，之后修改原查询不会改变已创建的搜索；输出使用独立 DTO reader，不继承来源查询的临时读取转换器。原 WHERE 整体与全文条件相与，根级 OR 分支不会绕过匹配。

输入有三种，调用方不需要拼接 SQL：

| 入口 | 输入含义 | 数据库行为 |
| --- | --- | --- |
| `plain(configuration, text, vector)` | 普通文本 | `plainto_tsquery` 分词，剩余词素以 AND 连接；不解释 tsquery 运算符/权重/前缀标签 |
| `web(configuration, text, vector)` | PostgreSQL 网页搜索语法 | `websearch_to_tsquery` 解释双引号短语、OR、减号，其他规则以 PostgreSQL 为准 |
| `query(new PgTsQuery(encoded), vector)` | 已编码词素与 tsquery 运算符 | 直接绑定原生 tsquery，不重新分词；可表达前缀、位置和否定等原生条件 |

普通文本和配置名拒绝 NULL、空白和不可传输 Unicode；全部为停用词的非空文本仍交给数据库，可能生成空查询而不命中。已编码的空 `PgTsQuery("")` 是合法值，NULL 查询不会被解释成“省略筛选”。`tsquery` 语法错误保留数据库异常。

配置、文本、encoded query 和归一化标志都绑定为 JDBC 参数。`plain` 与 `web` 的特殊字符含义不同，参数绑定只保证 SQL 边界，不会把搜索输入变成没有语法的字面量。输入规则依据 [PostgreSQL 17 查询控制](https://www.postgresql.org/docs/17/textsearch-controls.html)。

## 评分与 NULL

`FREQUENCY` 使用 `ts_rank`，`COVER_DENSITY` 使用 `ts_rank_cd`，默认是 FREQUENCY 且不归一化。词素的 A/B/C/D 标签沿用数据库默认权重；当前不添加另一套 Java 权重/分词系统。

`rankedBy` 接受以下枚举组合，重复标志不会重复处理：

| 标志 | PostgreSQL 原生作用 |
| --- | --- |
| `LOG_DOCUMENT_LENGTH` | 除以 1 + 文档长度对数 |
| `DOCUMENT_LENGTH` | 除以文档长度 |
| `MEAN_EXTENT_DISTANCE` | 除以匹配范围的平均调和距离，只允许 COVER_DENSITY |
| `UNIQUE_WORDS` | 除以不同词的数量 |
| `LOG_UNIQUE_WORDS` | 除以 1 + 不同词数量对数 |
| `RANK_OVER_RANK_PLUS_ONE` | 按 rank / (rank + 1) 缩放，不变成概率 |

原生 rank 返回 `float4`，SQL 显式转换为 `double precision` 后映射 Double；转换不会提高之前的计算精度。分数只用于当前查询与文档的原生比较，不承诺和 MySQL/HANA 或不同排名配置横向可比。COVER_DENSITY 需要词素位置信息，被 `strip` 移除位置的词素不能提供覆盖密度贡献。规则见 [PostgreSQL 17 排名函数](https://www.postgresql.org/docs/17/textsearch-controls.html#TEXTSEARCH-RANKING)。

匹配由 `@@` 决定，不能改成 `score > 0`：仅否定条件可以命中且得零分。SQL NULL vector 不命中；空 vector、空 query、停用词以及纯否定条件保留 PostgreSQL 原生语义。分页请给同分记录追加唯一稳定排序键。

## 支持边界与验收方式

- 来源限未投影、未分组、非 DISTINCT 的单物理表 `PgTsVector` 列；函数、SQL converter、外来 Proxy 列、CTE 或 JOIN 来源不能冒充物理检索列。搜索后的结果可以再组合 JOIN/CTE/UNION。
- 输出是一次新的投影边界，SMART 展示筛选留在外层；不会改变既定匹配与评分层。
- 当前拒绝分片合并，包括构造后、clone 或 CTE 后才加入的分片；不将单库结果类型读取的分片支持扩大成全文分布式查询支持。
- 小表通常适合顺序扫描，创建 GIN 不等于每次查询强制使用它。定向用例在独立事务内设置 `enable_seqscan=off`，通过完整 DSL SQL 与原参数的 EXPLAIN 验证可使用该 GIN，随后回滚恢复设置。
- 事务、生成列更新和 GIN 可见性按 PostgreSQL 原生规则验证；与 InnoDB 全文索引提交后可见性的差异不抹平。

验证记录见 [执行记录](execution.md)。更完整的全文索引选型与限制以 [PostgreSQL 17 索引类型](https://www.postgresql.org/docs/17/textsearch-indexes.html) 为准。
