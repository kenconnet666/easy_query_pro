# MySQL 全文查询

`MySqlSearch` 将一份 MATCH/AGAINST 规格同时用于筛选和评分，然后通过原 EntityQueryable 暴露结果。支持自然语言和布尔模式；搜索文本始终为 JDBC 参数，匹配模式由受控入口选择。

## 显式安装与调用

全文依赖完整查询作用域，需在普通增强安装之后显式安装根上下文：

```java
var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
        .setDefaultDataSource(dataSource)
        .useDatabaseConfigure(new MySQLDatabaseConfiguration())
        .customConfigure(services -> {
            MySqlExtensions.configure(services);
            MySqlExtensions.configureQueryScope(services);
        })
        .build();
```

普通 `configure` 保留已有 ExpressionFactory。`configureQueryScope` 显式安装 `MySqlProExpressionFactory`；应用定制工厂放在它之后注册，继承该公开类，或在自己的 SELECT 渲染中保留 `ProQuerySQLContext.withQueryContext`。没有根标记时，全文在生成 SQL 时明确抛出安装错误，不把未知作用域当作非分片。

输出使用带新 Proxy 的 DTO，分数字段映射为 Double：

```java
@EntityProxy
public class SearchHit extends Document {
    @Column("SEARCH_SCORE")
    private Double score;

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}

var source = query.queryable(DocumentProxy.createTable())
        .where(document -> document.enabled().eq(true));
var search = MySqlSearch.from(source,
        document -> MySqlSearchSpec.booleanMode("+coffee -tea", document.title(), document.body()));
var page = search.query(SearchHitProxy::createTable)
        .orderBy(hit -> {
            hit.score().desc();
            hit.id().asc();
        })
        .toPageResult(1, 20);
```

普通自然语言搜索使用 `MySqlSearchSpec.naturalLanguage("coffee", document.title(), document.body())`。指定的列集合必须有相应 FULLTEXT 索引，不能依靠分别索引两个列来代替一个匹配列集合的索引。

## 语义边界

- source 保存构造时的查询快照，后续修改调用者原查询不改变已定义搜索。原有根级 OR 条件先整体包组，再 AND MATCH。
- 输入是未投影、未分组的单物理表映射，列必须属于当前来源且为普通 String 映射。函数/CAST/SQL 属性转换列、重复列、外来列、派生表、CTE 和 DISTINCT 来源拒绝。
- 搜索词不可为 NULL 或空白，也拒绝 NUL 和未配对代理项。其他自然语言/布尔搜索语法由 MySQL 解释，不在 Java 中另建解析器。
- 布尔模式的 `+`、`-`、`~`、短语引号、前缀 `*` 等保留原生语义。只有负向排除词的查询可以返回空集；它不是“所有不含该词的行”。
- MATCH 在 WHERE 中保持 MySQL 的非零为真语义，不自动增加 `> 0`。`~` 可降低相关度并产生负贡献，不能把匹配结果错误压缩为正分数结果。
- SCORE 以 Double 保存原生数值，不承诺 0–1 范围，也不与 HANA/PG 分数比较。SELECT 与 WHERE 各出现一次搜索词，各自保留 JDBC 绑定。
- 输出 DTO 使用自己的映射和读取器，不继承源查询同名属性的临时 reader。`SEARCH_SCORE` 是保留输出列名，来源不能已有同名列。
- 外层筛选、排序、CTE、JOIN、UNION ALL、count/page 使用原查询 API。SMART 不改变内部匹配层；稳定分页需要明确的唯一键次排序。
- 当前不支持分片合并全文分数。构造时及 clone/CTE 后新增分片都拒绝；不能把每个分片各自的索引统计视为完整数据库的相关度。

动态 `asTable/asSchema` 的右表若只有来源重命名而没有其他查询操作，上游 EasyQuery 3.2.17 的直接实体 JOIN 优化可能丢失它。需要保持该右侧来源时，先用 `.select(document -> document)` 明确投影，再 JOIN，具体原因见 [HANA 全文的上游 JOIN 边界](hana-fulltext.md#查询边界)。这是上游组合限制，不是通过伪 WHERE 修补的搜索特例。

## 索引与事务

索引由应用迁移或 DBA 管理：

```sql
CREATE TABLE document (
    id INTEGER PRIMARY KEY,
    title TEXT,
    body TEXT,
    FULLTEXT text_ft(title, body)
) ENGINE=InnoDB CHARACTER SET utf8mb4;
```

本地验收以 MySQL 8.4.11 的 InnoDB 为准。没有匹配的 FULLTEXT 索引时数据库报错，不退成 LIKE。索引的字符集、排序规则、停用词、最小词长、ngram/语言分析器都属于数据库配置；普通词法分析器下的中文字符串测试只验证与原生查询一致，未宣称默认分析器能完成所有中文分词。

InnoDB 全文索引在事务提交时更新。实测同一 client 的事务中插入文档后，全文暂不命中；提交后命中。回滚的新文档不进入全文结果。API 保留原 client 的事务路径，不为了立即可见而偷偷提交，也不从另一执行器绕过事务。

当前未增加 query expansion 模式，也未统一停用词或排序规则。自然语言/布尔模式的依据见 [MATCH/AGAINST](https://dev.mysql.com/doc/refman/8.4/en/fulltext-search.html)、[布尔全文](https://dev.mysql.com/doc/refman/8.4/en/fulltext-boolean.html) 和 [InnoDB 全文索引](https://dev.mysql.com/doc/refman/8.4/en/innodb-fulltext-index.html)。

## 验证入口

`MySqlSearchDslTest` 验证绑定顺序、不可变来源、错库、缺少 query scope、后置工厂丢失根标记、普通列边界、fresh Proxy 和晚追加分片；消费者编译拒绝数字列、计算表达式及错误评分类型。

`MySqlSearchIT` 在客户端/服务端预编译两模式下建立真实索引，对比自然语言及布尔模式的原生命中和评分，覆盖引号、Unicode、短语、前缀、排除及负贡献、多列、NULL、未命中、缺索引、根级 OR、SMART、CTE/count/page、两个评分来源 JOIN/UNION ALL、reader 隔离和提交/回滚可见性。测试表随作用域删除，具体证据见 [执行记录](execution.md)。
