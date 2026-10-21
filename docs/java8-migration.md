# Java 8 迁移方案

状态：待确认，尚未开始改代码。

## 目标

Java 模块（五个生产模块、`integration-tests`、`consumer-tests`、`examples/*`）从 Java 17 降到 Java 8：用 `D:\soft\jdk1.8.0_181` 编译和运行测试，生产字节码为 52。上游 EasyQuery 3.2.17 的 `sql-core`、`sql-api-proxy` 都是 Java 8 字节码，降级后增强包的使用人群与上游一致。热重载通过 DebugTools + DCEVM altjvm 实现，见 [开发环境](development.md#idea-与-debugtools)。TypeScript 子项目不受影响。

## 规模

按正则统计，未计入 IDE 才能识别的隐式用法：

| 模块 | 文件 | 行数 | Java 9+ 语法命中 | Java 9+ API 命中 |
| --- | ---: | ---: | ---: | ---: |
| easy-query-pro-hana | 48 | 4,000 | 323 | 84 |
| easy-query-pro-dsl | 49 | 3,820 | 205 | 26 |
| easy-query-pro-dsl-hana | 27 | 2,119 | 90 | 24 |
| easy-query-pro-dsl-mysql | 21 | 1,296 | 65 | 6 |
| easy-query-pro-dsl-pgsql | 36 | 2,546 | 92 | 11 |
| integration-tests | 168 | 16,097 | 2,150 | 1,137 |
| examples | 8 | 867 | 77 | 15 |

- 语法：`var`（生产 92 个文件，测试约 2,400 处）、record（生产 34 个）、switch 表达式、`instanceof` 模式匹配、sealed/permits、文本块，以及私有接口方法。
- API：`List/Set/Map.of`、`Stream.toList()`、`String.isBlank/strip/repeat`、`Optional.isEmpty/orElseThrow()`、`readAllBytes` 等。

## 依赖与构建

| 项 | 现在 | Java 8 方案 |
| --- | --- | --- |
| 编译 | `maven.compiler.release=17` | `maven.compiler.source/target=1.8`；javac 8 不支持 `--release` |
| toolchain | `[17,18)` | `1.8`，本机 `toolchains.xml` 已注册 |
| JUnit | 6.1.3（要求 Java 17） | 5.14.4，5.x 的最后一条线，支持 Java 8 |
| HikariCP（测试/示例） | 7.0.2（Java 11 字节码） | 4.0.3，最后一个 Java 8 版本 |
| Connector/J 9.7.0、pgjdbc 42.7.14、ngdbc 2.30.11 | — | 不变，均为 Java 8 字节码 |
| Spring 示例 | Boot 3.5.16 | Boot 2.7.18；上游 `sql-springboot-starter` 同时提供 `spring.factories` 和 `AutoConfiguration.imports` |
| Javadoc | `<release>` + JDK 17 | `<source>8</source>` + JDK 8 工具链，并重新核对 doclint 规则 |
| 运行时断言 | `Runtime.version().feature() == 17` | 改为断言 `java.specification.version` 为 `1.8` |
| CI | `setup-java` jetbrains 17.0.14 | `setup-java` temurin 8；Maven 进程仍可用较新的 JDK 运行，由 toolchain 选 8 |

Maven 本身继续用系统 JDK 17 启动，只有编译器和测试 JVM 走 toolchain 选中的 JDK 8。这样可以避开 8u181 自带 CA 证书较旧、访问 Maven Central 时可能出现 TLS 问题。

## 做法

1. 先完成构建切换的提交：修改 POM、依赖和断言，此时生产模块无法编译，用编译错误清单作为待办。
2. 按依赖顺序逐个改模块：`dsl` → `hana` → `dsl-hana/mysql/pgsql` → `integration-tests` → `examples`。
   - 语法类优先用 IDEA 的批量 intention：Replace 'var' with explicit type、Convert record to class、Replace with old-style switch、Replace pattern variable with cast、Replace text block with string literal。
   - record 改成 final class，保留原有访问器名、`equals/hashCode/toString` 语义和紧凑构造器里的校验。
   - 集合工厂统一换成仓库内一个小工具类（不可变 List/Set/Map），不在调用点散落写 `Collections.unmodifiableList(Arrays.asList(...))`。
   - sealed 层次改为包私有构造器加 final 子类，用访问控制保留"封闭"的意图。
3. 每个模块完成后用 JDK 8 跑 `clean verify`，再跑对应数据库 profile；整体完成后跑 MySQL/PG/HANA 全量和 consumer 测试。
4. 整理提交与功能提交分开：构建切换、每个模块的语法降级、文档更新各自提交。

## 需要确认的取舍

- 测试也降到 Java 8（推荐）：只维护一个 toolchain，测试真实运行在 JDK 8 上，也能直接用 DCEVM 热重载。另一种做法是测试保留 Java 17、只把生产代码编译成 8，改动量减少约 3,300 处，但运行时只验证了 17，不能证明 Java 8 用户能正常使用。
- Spring 示例降到 Boot 2.7.18：Boot 2.7 的 OSS 支持已结束，仅作为 Java 8 用户的接入示例。也可以去掉 Spring 示例，只保留纯 Java 示例。
- 降级后，record 的语义需要靠测试和 IDE 检查保证，模式匹配和 switch 表达式的穷举性检查也会丢失。对原本靠 sealed + switch 穷举的地方，补上 `default` 分支并显式抛出异常。
