# 开发环境与验证

## JDK 与 Maven

本机使用 `C:\Users\lionheart\.jdks\jbr-17.0.14`，IDEA Project SDK 为 `jbr-17`。已在本机 `~/.m2/toolchains.xml` 注册该路径，项目编译和测试选择 `[17,18)` 工具链；即使 Maven 本身由系统 Java 25 启动，编译器和测试 JVM 仍使用 JDK 17。

Maven Wrapper 固定 Maven 3.9.16，wrapper 为官方 only-script 3.3.4。不要求安装全局 Maven，不更改系统 JAVA_HOME。首次运行需要网络以下载依赖。

其他机器需要在自己的 `~/.m2/toolchains.xml` 注册 JDK 17，CI 由 `actions/setup-java` 写入工具链。POM 使用标准 `toolchain` goal 和 `<toolchains><jdk><version>` 配置，供 Maven 与 IDEA 一致解析，不自动扫描其他 JDK。本机实验版 JDK 的发现曾触发插件异常，而 IDEA 会错误地将 `select-jdk-toolchain` 的 `discoverToolchains` 配置解析为匹配条件；标准配置避开这两个问题。

```xml
<toolchains>
    <toolchain>
        <type>jdk</type>
        <provides><version>17</version></provides>
        <configuration><jdkHome>/absolute/path/to/jdk-17</jdkHome></configuration>
    </toolchain>
</toolchains>
```

POM 使用 `release=17`，不启用 preview；Maven 堆上限 1 GiB，测试 JVM 上限 512 MiB。测试会断言实际运行在 Java 17，避免只验证字节码目标版本。

## 本地工作流

```powershell
# 快速验证：无数据库，无凭据。
.\mvnw.cmd clean verify

# 确认生产依赖边界。
.\mvnw.cmd dependency:tree '-Dscope=compile'

# 本地定位数据库问题；完整回归通常交给 GitHub Actions。
.\tools\test-databases.ps1 -Database mysql
.\tools\test-databases.ps1 -Database postgres
.\tools\test-databases.ps1 -Database all
```

数据库脚本面向 Windows PowerShell 7 和 WSL Debian，不依赖 Docker Desktop。它生成临时密码和独立 Compose project，使用空闲端口，并在 finally 中删除自己的容器、卷、网络。镜像作为 Docker 缓存复用，脚本不清理用户共享镜像。

供 IDEA 使用的持久开发实例由 `tools/databases.ps1 -Action start` 启动，Compose project 为 `eqp-dev`，MySQL 端口 53306、PostgreSQL 端口 55432；数据库和用户均为 `eqp`。`stop` 保留容器和卷，后续 `start` 从自己的已有容器恢复凭据，发现不一致时拒绝覆盖。

`tools/database-password.ps1 -Database mysql|postgres|hana` 将相应本地密码复制到剪贴板，不写入仓库文件。该脚本兼容 Windows PowerShell 5.1 和 PowerShell 7；数据库生命周期脚本仍使用 PowerShell 7。IDEA 可以使用默认驱动或本地 Maven 缓存的对应 JAR；HANA 已验证必须使用本项目的 ngdbc 2.30.11，IDEA 默认下载的 2.17.12 在当前 IDE 运行时出现 `getSubject is not supported`。

容器限制定义在 `infra/compose.yaml`：MySQL 2 GiB、PostgreSQL 1 GiB，memory+swap 与 memory 相等，两者各限 1 CPU；内存限制不足或不一致时脚本拒绝继续测试。镜像固定为 Linux amd64 的版本及 digest，资源不会因为上游浮动 tag 变化而悄悄更换。

凭据只通过进程环境传递，不写入 POM、Compose 明文值或仓库文件。测试使用 `eqp_product` 表，只有成功创建后才进入清理流程，已有同名表会使测试失败而不会被删除；PostgreSQL 额外使用每次随机创建的 schema。

运行已有实例可配置环境变量，再执行对应 profile：

| Profile | 环境变量 | 执行内容 |
| --- | --- | --- |
| `mysql` | `EQP_MYSQL_JDBC_URL`、`EQP_MYSQL_USER`、`EQP_MYSQL_PASSWORD` | APT Proxy、CRUD、Unicode/decimal 往返、参数绑定、事务提交/回滚 |
| `postgres` | `EQP_PG_JDBC_URL`、`EQP_PG_USER`、`EQP_PG_PASSWORD` | 同一组 JDBC 契约，独立 schema |
| `hana` | `EQP_HANA_JDBC_URL`、`EQP_HANA_USER`、`EQP_HANA_PASSWORD` | 真实 HANA 连接、临时列存表、绑定参数、中文/decimal 与事务验证，不代表 HANA 方言适配完成 |

```powershell
.\mvnw.cmd clean verify '-Pmysql,postgres'
.\mvnw.cmd clean verify -Phana
```

三库 `src/it/*/java` 始终注册为测试源根，三个驱动仅进入集成测试模块的 test classpath，IDEA 默认导入即可解析所有测试。Profile 只选择 Failsafe 执行范围：`MySql*IT`、`Postgres*IT`、`Hana*IT`，同时启用多个 profile 时合并范围；没有 profile 的 `verify` 只编译数据库测试而不连接数据库。切换 profile 不再依赖 clean 来避免误跑残留测试。主动启用的 profile 缺少环境变量会失败，不会静默跳过。

当前测试驱动固定为 MySQL Connector/J 9.7.0、PostgreSQL JDBC 42.7.14、SAP ngdbc 2.30.11；生产模块不传递这些驱动。

## HANA Docker 开发实例

HANA 是核心目标数据库。`infra/hana.compose.yaml` 固定 SAP 官方 `saplabs/hanaexpress:2.00.088.00.20251110.1` 的 digest，使用独立 `eqp-hana` Compose project 和持久卷。容器限制 12 GiB 内存、无额外 swap、4 CPU，采用数据库服务版，不安装 XS 应用服务。

```powershell
.\tools\hana.ps1 -Action start
.\tools\hana.ps1 -Action status
.\tools\hana.ps1 -Action test
.\tools\hana.ps1 -Action test -IntegrationTest 'HanaSequenceIT'
.\tools\hana.ps1 -Action stop
```

端口只绑定 loopback：HXE 业务租户使用 `127.0.0.1:39041` / `jdbc:sap://127.0.0.1:39041`；SYSTEMDB 管理连接使用 `127.0.0.1:39017`。集成测试显式拒绝 SYSTEMDB，避免连接成功却验证错数据库。首次启动从 `EQP_HANA_PASSWORD` 读取密码，未设置时生成随机密码；再次启动/测试从已存在容器的环境读取，不输出密码。已有数据卷而没有容器时，必须提供原始密码，脚本不会猜测、覆盖或删除数据库。

`test` 默认增量编译，`-IntegrationTest` 传给 Failsafe 选择定向测试；需要干净构建时显式添加 `-Clean`。CI 始终执行完整 clean 验证。本地开发不等待长时间 CI 结束，下一次提交前读取前一提交的结果并处理失败。

SAP 启动入口要求密码 JSON 文件。启动脚本从环境变量生成该文件，仅存放在容器的私有 tmpfs 中，由 SAP 初始化流程消费；健康检查确认 HXE 租户的认证 SQL 可用后删除可能遗留的文件。仓库、宿主配置和持久数据卷不保存明文密码文件。本项目启动脚本接受 16–64 位字母数字密码，须同时包含大写、小写和数字。

部署执行 SAP 官方入口的许可接受参数。参考 [SAP 官方 Docker 安装说明](https://developers.sap.com/tutorials/hxe-ua-install-using-docker)与 [官方镜像](https://hub.docker.com/r/saplabs/hanaexpress)。本机 Debian/WSL 是开发验证环境，不能从测试成功推导生产部署认证。

HANA 所需 IPC sysctl 和文件句柄限制在 Compose 中配置。若宿主系统检查不满足，先核对错误和现值再调整相应内核参数，不关闭整个系统检查。CI 在独立 runner 上设置所需 AIO、memory map 和 early kill 参数；需要至少约 14 GiB 可见内存，资源不足会失败。

`tools/hana.ps1 stop` 保留容器和数据，避免下次启动丢失初始化凭据。清空开发数据库属于单独的显式操作，普通测试不会删除持久卷。

## IDEA 与 DebugTools

IDEA 已识别父项目和六个子模块，Project SDK 为指定 JBR 17。通过 Maven 导入同步依赖；代理源由 `sql-processor:3.2.17` 生成在 `integration-tests/target/generated-test-sources/test-annotations`，不提交生成文件。

已核对 DebugTools 官方 [热重载说明](https://debug-tools.cc/guide/hot-reload.html)和 [JDK 安装说明](https://debug-tools.cc/guide/install.html)。本机插件为 5.3.0，JBR 17 已通过 `-XX:+AllowEnhancedClassRedefinition -version` 启动检查；这项检查不等于已经完成应用热重载验证。

当前会话已暴露 `get_debug_tools_status`、`execute_debug_tools_run_configuration` 和 `compile_and_reload_modified_files`。后续有运行中的调试程序时，可通过 DebugTools Hotswap 启动并重编译/重载函数实现。按官方文档核对 Async Stack Traces 设置；本轮没有改动全局调试设置，也没有在普通 Maven 构建中加入 agent。

修改实体字段、注解、代理签名、服务注册或元数据缓存时，重新执行 APT 并重建受影响的 EasyQuery runtime。官方文档未列出 EasyQuery 专属热重载适配，不能假设热重载自动刷新其元数据。正式验收使用无热重载 agent 的 clean 构建。

## GitHub Actions

`CI` 工作流由 push、pull_request、workflow_dispatch 触发。Windows/Linux 使用 JetBrains JDK 17.0.14 构建；MySQL、PostgreSQL 通过 matrix 在独立 runner 上并行运行，各自启动对应 profile、校验内存并保存独立报告，设置 fail-fast=false。HANA 仍是独立 job，所有 job 没有相互等待的依赖。失败时保留报告和数据库日志，最后总是清理本 job 的测试资源。

HANA Docker job 默认运行，使用临时密码、独立卷和同一份 HANA Compose 配置，运行 JDBC 检查后清理所有测试资源。手动输入 hana 额外启用外部实例检查，需要在 `hana-test` Environment 配置三项同名 secrets，并确保 runner 能访问实例；只有这项额外检查可以显示 skipped。

CI 保存各模块 JAR 和测试报告 7 天。当前尚无生产功能发布，CD 的边界是交付可下载构建产物，不执行 Maven Central 发布或数据库迁移。报告必须对应当前 commit 的运行，不能以旧版本成功替代。
