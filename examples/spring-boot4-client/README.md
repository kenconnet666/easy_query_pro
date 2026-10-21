# Spring Boot 4 独立消费者

此示例使用 Java 17、Spring Boot 4.0.8 和 EasyQuery 3.2.17 的 `sql-springboot4-starter`，消费以 Java 8 字节码发布的增强包。它和 [Spring Boot 3 示例](../spring-client/README.md) 的接入方式相同：

- 继续使用上游 starter 的自动配置；
- 应用自己导入一份 `ProConfiguration`，不新增 starter。

`ProConfiguration` 与 Boot 3 示例逐字一致。v4 starter 依赖 v3 starter，复用其中的 `SpringBootStarterBuilder`、`EasyQueryProperties` 和 Spring 连接管理，只替换了适配 Boot 4 包结构的自动配置类。

```java
@SpringBootApplication
@Import(ProConfiguration.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

数据库选择与 Boot 3 示例相同：MySQL 使用 `easy-query.database=mysql`，PostgreSQL 使用 `pgsql`，HANA 使用 `sql92` 并由 `ProConfiguration` 安装完整的 HANA 服务。

## 验证内容

`SpringBoot4ConsumerTest` 用 `SpringApplicationBuilder` 启动真实应用，上游两个 starter 的自动配置清单都会参与加载，所以能暴露 Boot 4 下 v3/v4 配置共存的问题。只用 `ApplicationContextRunner` 显式列出配置类，发现不了这类问题。

- 离线用例：分别在 MySQL、PostgreSQL、HANA 三种配置下启动应用，验证以下几点：
  - 只有一个 client；
  - Spring 连接管理已接管连接；
  - Pro 方言服务和 HANA 函数已安装；
  - 渲染出的 SQL 使用绑定参数；
  - 关闭应用时会停止 client 的工作线程。
- 实库用例：只有在指定 `consumer.database` 时才运行。
  - 由 Boot 4 根据 `spring.datasource.*` 自动创建 Hikari 连接池。
  - `@Transactional` 同时包住 `JdbcTemplate` 和 EasyQuery 的写入，抛出异常后一起回滚。
  - `TransactionTemplate` 正常提交。
  - 测试只操作本次创建的 `EQP_BOOT4_...` 表，结束时删除。

执行方式与其他消费者相同。Maven 进程需要 JDK 17 及以上：

```powershell
.\mvnw.cmd --batch-mode verify -Pconsumers -pl consumer-tests -am "-Dinvoker.test=examples/spring-boot4-client"
.\mvnw.cmd --batch-mode verify -Pconsumers -pl consumer-tests -am "-Dinvoker.test=examples/spring-boot4-client" -Dconsumer.database=postgres
```

实库所需的环境变量见 [Java 消费者](../java-client/README.md)。这里验证的是上述锁定版本、单数据源场景下的接入。
