# Java 17 独立消费者

这个工程没有本仓库 parent，也不引用其他模块的源码或 integration-tests。它明确选择三种数据库的增强包、JDBC 驱动和 HikariCP，方便对照安装位置；实际应用可以只保留使用的数据库分支与依赖。

- `Clients` 展示“数据库配置 → 服务扩展 → 映射/handler → 首次实体 metadata”的顺序。MySQL/PG 的查询作用域显式安装，不覆盖没有采用该能力的应用工厂。
- `Product` 是本工程自己的实体，`ProductProxy` 每次由 EasyQuery 3.2.17 processor 生成，不提交生成文件。
- `ProductQueries` 使用共同的 NULL-safe 比较与精确 CAST，返回普通 `EntityQueryable`。
- `ArtifactConsumerTest` 在没有数据库时验证三库 SQL、原生类型 API、JAR 来源和新生成的 Proxy。
- `DatabaseConsumerTest` 只有显式指定数据库时才运行，创建唯一测试表，验证 CRUD、Unicode/DECIMAL、分页、回滚和池连接恢复，并在 finally 删除自有表。

在仓库根目录用 Maven Wrapper 执行隔离消费验证：

```powershell
$env:JAVA_HOME = 'C:\Users\lionheart\.jdks\jbr-17.0.14'
.\mvnw.cmd --batch-mode verify -Pconsumers -pl consumer-tests -am
```

Maven Invoker 把候选 JAR、实际 POM 和可达父 POM放入 `consumer-tests/target/repository`，将本工程 clone 到 `consumer-tests/target/projects` 后启动独立 Maven `clean verify`。子构建不在原 reactor 内，不共享原工程的 target/classes。另两个负例分别漏选 PG 模块与指定不存在的版本，校验错误确实来自对应编译/依赖解析阶段。

从任意独立目录消费已安装或已发布版本时，可以直接：

```text
mvn --batch-mode clean verify -Deasyquerypro.version=<published-version>
```

该命令不会自动安装本仓库源码。当前 SNAPSHOT 尚未对外发布时，优先使用上面的隔离验证；如需手动安装到个人 repository，应由使用者按自己的发布流程执行。

真实数据库测试复用环境变量，不把密码写入 POM或日志：

| `consumer.database` | JDBC URL / 用户 / 密码环境变量 |
| --- | --- |
| `mysql` | `EQP_MYSQL_JDBC_URL` / `EQP_MYSQL_USER` / `EQP_MYSQL_PASSWORD` |
| `postgres` | `EQP_PG_JDBC_URL` / `EQP_PG_USER` / `EQP_PG_PASSWORD` |
| `hana` | `EQP_HANA_JDBC_URL` / `EQP_HANA_USER` / `EQP_HANA_PASSWORD` |

```powershell
.\mvnw.cmd --batch-mode verify -Pconsumers -pl consumer-tests -am -Dconsumer.database=hana
```

URL必须指向专用测试库。该测试只操作本次创建的 `EQP_CONSUMER_...` 表，不运行 identity/default 的风险探测、不修改用户表或持久开发容器。用户名需要在当前 schema 建表与删表的权限；完全只读用户的应用接入会在后续权限契约中单独验证。

本示例的普通 Java 事务由 EasyQuery 管理。Spring 应用应使用经过验证的 Spring 连接/事务集成，不能仅把这里的 client 放进 Spring 容器就假定 `@Transactional` 生效。
