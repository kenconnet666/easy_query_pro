# 独立安装与公开声明验证

在 ts-orm 目录运行 `pnpm test:consumer`。该检查先构建候选，再创建真实 tarball，在本次专属临时目录中离线安装，最后编译和运行独立消费者。它没有依赖工作区 link、源码 alias 或库自身的 test tsconfig。

消费者使用 ESM，显式关闭自动安装 peer、安装脚本和外部 pnpm hooks，并设置 `types: []`、`skipLibCheck: false`。环境中不安装 pg、mysql2、SAP 客户端、@types/node 或 @types/pg；核心查询构建不应因此要求这些可选类型依赖。

检查包含：

- 标准包名导入 core，验证三个 driver 子路径能在没有实际驱动时加载其适配函数；真正连接仍由应用传入 pool。
- 固定十次 LEFT JOIN、四层 CTE、扩展 namespace 和业务金额 codec 的完整类型链。
- nullable、数值类型、业务映射、生成列、UUID 方法和缺失 namespace 的消费者编译负例；类型意外变成 any 时负例会失败。
- package exports 不开放内部 src 子路径，运行使用 dist JavaScript。
- 用编译器实际解析文件清单检查：除本例与编译器标准库外，只读取安装包的 dist 声明，没有读取开发仓库源码。
- 运行时验证 JOIN/CTE SQL、参数绑定和含 NULL、精确金额的 decoder。
- 遍历每个 declaration map，确认目标源文件实际位于安装包内。

包包含 dist 与 src，后者用于源码导航；源码存在不意味着消费者编译走源码。导出入口仍指向 dist，解析清单单独证明了这一点。测试工具、fixture、临时凭据和工作区产物不进入 tarball。

工具从锁定 TypeScript package.json 的 bin 元数据寻找 CLI，不假设私有 bin 子路径被 exports 开放；TS 7 平台包的标准库也由其已声明依赖定位。消费者正确使用 import.meta.resolve 解析 ESM 包，不为测试方便额外承诺 CommonJS 入口。

每次临时目录由 mkdtemp 创建。结束时核对真实父目录及本任务前缀后清理；失败也清理，清理错误与原验证错误一起保留。共享 pnpm 缓存保持原状。整个过程不会发布 npm 包，当前包仍 private。

CI 在 Windows/Linux、Node 22/24 四个任务分别运行该检查，保存编译器 diagnostics 和消费者日志。最初 Windows / Node 24 / TS 7.0.2 的本地观测约为 17 MiB 编译器内存、16,973 次类型实例化；这是当前固定 fixture 的单次观测，不是性能上限或其他机器的耗时承诺。后续扩展类型图时可以对照这些记录识别明显退化。

这项检查覆盖发布形状和核心消费；真实数据库执行由独立的三库契约与 HANA CI 负责。可选驱动的应用仍应安装相应 peer，并采用文档中的连接与日期配置。
