# Spring 独立消费者

此示例针对 Java 17、Spring Boot 3.5.16 / Spring Framework 6.2.19、EasyQuery 3.2.17。它使用独立 POM、Spring Boot 官方 parent 和自己的 APT 实体，不继承本仓库 parent，不读取生产模块源码或现有 Proxy。其他 Spring 主版本需要各自验证，不能用本示例的结果替代兼容声明。

## 配置位置与顺序

在应用中导入 `ProConfiguration`，继续使用原 `sql-springboot-starter` 的自动配置：

```java
@SpringBootApplication
@Import(ProConfiguration.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

`ProConfiguration` 是可阅读、可修改的应用配置，不是另一个 starter：

1. `StarterConfigurer` 安装选定数据库的 Pro 服务与查询作用域。
2. 显式 `EasyQueryClient` Bean 调用上游公开 `SpringBootStarterBuilder`，保留 name conversion、schema、结果上限等原有选项，以及 SpringConnectionManager / SpringDataSourceUnitFactory。
3. 暂缓上游组件注册，先安装 Pro 默认 handler，再调用原 `EasyQueryInitializeOption.addComponents`，让应用自己的 `JdbcTypeHandlerReplaceConfigurer`、converter、interceptor 等按原规则注册，最后补齐缺少的默认 SQL converter。上游 converter 按具体类拒绝重复注册，不是覆盖容器；`configureMappings` 保留已经注册的同类实例。
4. 上游默认 client Bean 因已有 Bean 而退让，原 `EasyEntityQuery` 包装仍由上游创建。第一次读取实体 metadata 发生在上述初始化完成之后。
5. Spring 关闭时停止这个 client 的工作任务；如果组件安装在 client 返回成 Bean 之前失败，工厂自己停止任务并保留原异常与清理异常。连接池仍由创建它的 Spring Bean 负责关闭。

应用还需要替换某个 Service 时，将替换注册写在 `proServices` 的默认安装之后。这个示例明确选择一个 StarterConfigurer；不要同时声明多个未指定优先关系的同类型 Bean。

## 数据库选择

MySQL 使用 `easy-query.database=mysql`，PostgreSQL 使用 `pgsql`。HANA 使用上游提供的中性 `sql92` 入口，再由本配置安装完整 HANA 服务：

```yaml
spring:
  datasource:
    url: ${EQP_HANA_JDBC_URL}
    username: ${EQP_HANA_USER}
    password: ${EQP_HANA_PASSWORD}
easy-query:
  enable: true
  database: sql92
  print-sql: false
```

3.2.17 的 `DefaultDatabaseConfiguration.configure` 为空，SQL92 在这里作为构建前扩展入口；运行时 SQLKeyword、ExpressionFactory、SQLFunc、JDBC、metadata、migration 等仍由完整 `HanaDatabaseConfiguration` 提供。它不把 HANA 伪装成 MySQL。

不要保留 UNKNOWN 后仅寄希望于 StarterConfigurer：上游会先选择数据库，UNKNOWN 在进入钩子之前就抛错。示例有独立负例证明这个顺序。也不要直接填写上游枚举不存在的 `hana`。

应用可以提供一个自己的 `HanaOptions` Bean；没有提供时使用 `HanaOptions.DEFAULT`，每个 client 单独捕获。测试使用容量 1024 的 options，并断言 runtime 保留同一个实例。

## Spring 事务

业务统一使用 `@Transactional` 或 `TransactionTemplate`。Spring 通过同一个 DataSource 将 JDBC 连接绑定到当前事务，原 EasyQuery 的 Spring 连接管理负责获取和释放它。不要在同一次 Spring 事务内部再调用 `EasyQueryClient.beginTransaction()` 启动另一套事务上下文。

真实用例让 `JdbcTemplate` 先插入未提交数据，再立即通过 EasyQuery 读取；随后通过 EasyQuery 写入，最后故意抛异常。两条写入都回滚才算通过，这比只检查“配置类名正确”更能证明事务参与。另一个 TransactionTemplate 用例验证正常提交、应用 handler、池连接归还/autoCommit 恢复及 Spring 关闭池。

## 执行方式

在仓库根目录使用指定 JDK 17：

```powershell
$env:JAVA_HOME = 'C:\Users\lionheart\.jdks\jbr-17.0.14'
.\mvnw.cmd --batch-mode verify -Pconsumers -pl consumer-tests -am -Dinvoker.test=examples/spring-client
```

默认只运行离线 ApplicationContextRunner 与 UNKNOWN 负例，实库用例明确跳过。选择真实数据库时，沿用 [Java 消费者](../java-client/README.md) 的环境变量，追加：

```text
-Dconsumer.database=mysql
-Dconsumer.database=postgres
-Dconsumer.database=hana
```

只选择其中一个。每轮创建唯一 `EQP_SPRING_...` 表，finally 删除；Hikari 最多两条连接，密码只读环境变量。已接入的消费者 CI 会在原数据库 job 中选择相应数据库，不增加新实例或突破已有内存限制。

这里验证的是上述锁定版本和单 DataSource 的 Spring 接入。分布式事务、JTA、多事务管理器自动选择，以及其他 Spring 主版本不由本例隐式承诺。Spring 测试方式参考 [官方自动配置测试说明](https://docs.spring.io/spring-boot/3.5/reference/features/developing-auto-configuration.html)，版本要求见 [Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。
