# Java 8 基线与迁移记录

状态：2026-10-21 完成。Java 模块（五个生产模块、`integration-tests`、`consumer-tests`、`examples/java-client`）从 Java 17 降到 Java 8，产物 class 主版本为 52。降级后，增强包的使用人群与上游 EasyQuery 3.2.17 一致；上游 `sql-core`、`sql-api-proxy` 本身也是 Java 8 字节码。TypeScript 子项目不受影响。

## 构建与依赖

| 项 | Java 17 时期 | 现在 |
| --- | --- | --- |
| 编译 | `maven.compiler.release=17` | `maven.compiler.source/target=1.8`；javac 8 不支持 `--release` |
| toolchain | `[17,18)` | `[1.8,1.9),[8,9)`（属性 `jdk.toolchain.version`），本机为 `D:\soft\jdk1.8.0_181` |
| Maven 进程 | 任意 JDK | JDK 17 及以上：运行 Maven 本身和 Spring 示例；也避免 8u181 较旧的 CA 证书 |
| JUnit | 6.1.3 | 5.14.4（5.x 的最后一条线） |
| HikariCP（测试/示例） | 7.0.2 | 4.0.3（最后一个 Java 8 版本） |
| Connector/J 9.7.0、pgJDBC 42.7.14、ngdbc 2.30.11 | — | 不变，本身就是 Java 8 字节码 |
| 注解处理 | 根 POM `proc none`，测试 `proc full` | 都去掉：`-proc:full` 是较新的 javac 才有的参数；生产模块 classpath 上没有注解处理器 |
| Javadoc | `<release>` + JDK 17 | `<source>8</source>` + JDK 8，doclint 规则不变 |
| 运行时断言 | `Runtime.version().feature() == 17` | `java.specification.version == "1.8"` |
| CI | `setup-java` jetbrains 17.0.14 | `setup-java` temurin，同时安装 8 和 17 |

## 接入示例

| 示例 | Java / 框架 | 验证内容 |
| --- | --- | --- |
| [java-client](../examples/java-client/README.md) | 经 toolchain 使用 JDK 8 | 独立 POM、全新 APT、三库 SQL、可选实库契约 |
| [spring-client](../examples/spring-client/README.md) | Java 17、Spring Boot 3.5.16、`sql-springboot-starter` | 上游 starter 自动配置 + 应用导入的 `ProConfiguration` |
| [spring-boot4-client](../examples/spring-boot4-client/README.md) | Java 17、Spring Boot 4.0.8、`sql-springboot4-starter` | 用真实 `SpringApplication` 启动，跑完整自动配置；`ProConfiguration` 与 Boot 3 示例相同 |

Spring 应用沿用 EasyQuery 原来的 starter 接入方式。增强包没有自己的 starter，也不引入 Spring 依赖。

## 新代码约束

生产代码和测试都按 Java 8 编译，并在 JDK 8 上运行。下面这些写法会直接导致编译失败，统一按右侧替换：

| Java 9+ 写法 | Java 8 写法 |
| --- | --- |
| `var` | 显式类型 |
| `record` | `final class` + `private final` 字段 + 访问器；值语义需要时补 `equals`/`hashCode`/`toString` |
| switch 表达式、`case X ->` | 传统 `case X:` 语句；赋值形式先声明变量再在各分支赋值；穷举枚举补带值的 `default` 异常 |
| `x instanceof T t` | `instanceof` 后显式强转，必要时先声明局部变量 |
| 文本块 `"""` | 字符串拼接 |
| `sealed`/`permits` | 包私有构造器 + final 子类 |
| 接口 `private` 方法 | 移到包内辅助类 |
| `try (existing)` | `try (Type alias = existing)` |
| 匿名类菱形 `new X<>() {…}` | 写出类型参数 |
| 生产代码 `List.of`/`List.copyOf`/`Set.of`/`Set.copyOf`/`Map.copyOf` | 所在基础模块的 `internal.ImmutableCollections`：`listOf`/`listCopyOf`/`setOf`/`setCopyOf`/`mapCopyOf`，同样拒绝 null、不可变 |
| 生产代码多键值对 `Map.of` | `ImmutableCollections.<K, V>mapBuilder().put(…).build()`，拒绝 null 与重复键 |
| 生产代码 `Stream.toList()` | `.collect(ImmutableCollections.toList())`（不可变，允许 null） |
| 测试中的 `List.of`/`Set.of`/`Map.of` | `Arrays.asList`、`new HashSet<>(Arrays.asList(…))`、`Collections.singletonMap` 或 `LinkedHashMap` |
| `String.isBlank()` | 上游 `EasyStringUtil.isBlank`（判空语义相同，且对 null 安全） |
| `String.repeat`/`strip`/`stripLeading` | `String.join("", Collections.nCopies(n, s))` / `trim()` / `replaceFirst("^\\s+", "")` |
| `Optional.isEmpty()`/`orElseThrow()` | `!isPresent()` / `get()` |
| `StringBuilder.isEmpty()` | `length() == 0` |
| `Duration.toMinutesPart/toSecondsPart` | `toMinutes() % 60` / `getSeconds() % 60` |
| `Path.of`、`Files.readString/writeString`、`InputStream.readAllBytes/readNBytes` | `Paths.get`、`Files.write(…getBytes(UTF_8))`、显式读取循环或 `DataInputStream.readFully` |
| `BigInteger.TWO`、`Collection.toArray(IntFunction)`、`ClassLoader.getPlatformClassLoader()` | `BigInteger.valueOf(2)`、`toArray(new T[0])`、`ClassLoader.getSystemClassLoader().getParent()` |

另外两条 JDK 8 特有的限制：

- javac 8 对局部类的构造器引用（`LocalClass::new`）推断有缺陷，会报"an enclosing instance … is not in scope"，改用 `scope -> new LocalClass(scope)`。用 JDK 17 加 `--release 8` 编译发现不了这个问题，只有真正的 javac 8 才会报。
- JDK 8 的 Javadoc doclint 更严格：注释里的 `>`/`<` 要放在 `{@code}` 里，类注释中不能用 `@param` 描述字段（原 record 组件的说明已挪到构造器上）。

## 迁移方式与验证

改写借助一个一次性工具完成，它基于 JDK 17 javac 的带类型语法树：`var` 的类型、record 的组件和构造器、switch 和 instanceof 的作用域、`Stream.toList` 与 EasyQuery 自身 `toList()` 的区分，都按编译器解析出的结果改写，没有用文本替换。工具每轮只应用不重叠的最内层改写，三轮后收敛，共改写约 3,750 处。剩余的生产代码 13 处、测试 30 余处按上表手工处理。工具只是迁移手段，没有提交到仓库。

`DslCompilationTest` 里的 140 段代码片段用同样的方式改写：用 `--release 8` 检查，期望编译通过的片段零错误，期望失败的片段第一个错误仍落在原语句上；最后再用真正的 javac 8 跑一遍。

迁移后的验证全部在 JDK 8u181 上完成：

- 离线测试 281 个；
- MySQL/PostgreSQL 实库 IT 130 个；
- HANA 实库 IT 146 个；
- 5 个产物 jar 都是 class 52；
- JDK 8 javadoc doclint 零错误、零警告。

使用方与 Spring 接入的结果见 [执行记录](execution.md)。
