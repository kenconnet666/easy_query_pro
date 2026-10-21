# Java 产物与 API 文档

五个生产模块均提供二进制 JAR、`sources` 和 `javadoc` classifier，坐标与模块版本一致。当前工程版本仍是 `1.0-SNAPSHOT`；真实 POM 和父 POM 随独立消费者一起验收。测试模块不发布，产物构建不自动创建发布标签或执行 deploy。

| artifactId | 用途 |
| --- | --- |
| `easy-query-pro-hana` | HANA 底层适配、JDBC、函数、元数据与迁移 |
| `easy-query-pro-dsl` | 通用精确类型与表达式、窗口和扩展基础 |
| `easy-query-pro-dsl-hana` | HANA 类型与原生查询来源 |
| `easy-query-pro-dsl-mysql` | MySQL 类型、JSON、时长、全文与 RANGE |
| `easy-query-pro-dsl-pgsql` | PostgreSQL 类型、JSON、数组、interval、全文与 RANGE |

groupId 为 `org.dpdns.zerodep.easy_query_pro`。应用选择自己需要的数据库模块；已有上游 processor 继续使用 `com.easy-query:sql-processor:3.2.17`，不会生成另一个 Pro processor。

## 构建与消费

默认 `verify` 保持原来的快速构建。显式 `artifacts` profile 在 package 阶段附加源码和 API 文档：

```powershell
.\mvnw.cmd --batch-mode clean verify -Partifacts
```

生成位置为各生产模块的 `target/`，例如：

```text
easy-query-pro-dsl-1.0-SNAPSHOT.jar
easy-query-pro-dsl-1.0-SNAPSHOT-sources.jar
easy-query-pro-dsl-1.0-SNAPSHOT-javadoc.jar
```

API HTML 同时位于 `target/reports/apidocs/`。Javadoc 使用相同的 JDK 8 工具链、UTF-8 和 384 MiB 上限。doclint 保留结构、引用等检查，仅不要求每个简单访问器都有机械的 missing 标签；生成错误会失败。外部 API 链接自动探测关闭，生成不依赖其他站点可达。

源码使用稳定的 Maven Source Plugin 3.4.0 `jar-no-fork`，文档使用 Javadoc Plugin 3.12.0 `jar`。采用 [Source Plugin 的附加工件流程](https://maven.apache.org/plugins/maven-source-plugin/usage.html) 和 [Javadoc classifier 配置](https://maven.apache.org/plugins/maven-javadoc-plugin/faq.html)，不复制源代码到另一个项目充当源码包。

## 独立 classifier 验收

```powershell
.\mvnw.cmd --batch-mode clean verify '-Pconsumers,artifacts' `
  -pl consumer-tests -am
```

该命令在现有隔离 repository 中暂存主 JAR、真实 POM 和十个附加 classifier，再 clone Java/Spring 示例并启动独立 Maven。Invoker 对 reactor 依赖默认只带主 JAR，所以 `consumer-tests` 在 artifacts profile 中使用其官方 `extraArtifacts` 显式请求十个附加工件；见 [Invoker 安装参数](https://maven.apache.org/plugins/maven-invoker-plugin/install-mojo.html)。

Java 消费者使用 Maven Dependency Plugin 按真实 dependency classifier 解析 sources/javadoc，缺失任何一个都会失败。JUnit 随后验证：

- 五个库的运行类来自 JAR，示例自己的 Proxy 由本次 APT 新生成。
- 十个附加包全部解析到独立工程；每个源码包存在对应 Java 文件且没有 `.class`。
- 每个 Javadoc 包存在首页和对应公开类页面。
- ProDsl 的中文说明完整保留，新公开方法 `denseRank` 出现在当前 API 文档中。

这些断言不读取主仓库的源码或 `target/classes`。默认非 artifacts 消费者明确跳过 classifier 检查，数据库 profile 继续专注真实 CRUD/事务。Windows/Linux 的独立消费者 CI 同时启用 artifacts，上传五个模块的 JAR/POM 和消费者报告；完整 CI 不在本地阻塞等待。

## CI 排队

快速的编译、消费者与 MySQL/PG 任务各按 ref 和矩阵项取消旧运行。HANA Docker 使用独立 ref 组，保留正在运行的作业，以免连续提交反复打断数据库启动和完整验收；默认仅保留一个 pending 候选，后到的候选替换较早的 pending。手动外部 HANA 使用固定 `hana-test` 环境组，跨 ref 串行。

这里采用 [GitHub job concurrency 的默认单 pending 语义](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)，没有开启积累大量任务的 `queue: max`。不能按提交时间推定执行顺序，也不能把被替换的 pending 标成成功。任务级规则不会追溯更改已经按旧 workflow 启动的作业；验收仍查看具体 SHA 的真实结果。

版本、限制与升级步骤见 [兼容与迁移](compatibility.md)，功能完整度按 [计划](plan.md) 和 [执行记录](execution.md) 的未关闭项继续追踪。
