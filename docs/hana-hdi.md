# HANA HDI 与真实参数化来源测试

目标是验证 HANA 2.0 本地部署的计算视图与表函数能力，分别部署真实工件并通过原 EntityQueryable 消费。

## 服务与权限

官方 Express 镜像中的 SYSTEMDB 使用 39017，HXE 租户使用 39041。数据库服务版没有为 HXE 自动启用 HDI，需要在 SYSTEMDB 添加租户的 diserver。`infra/hana/enable-hdi.sql` 只针对固定的测试租户 HXE，已存在时不重复添加；配套 shell 检查 HXE 的 `_SYS_DI.CREATE_CONTAINER` API 是否就绪。本地 `tools/hana.ps1 start` 和 HANA CI job 都执行同一入口。

这一步来自 [SAP HANA Platform 的租户 HDI 启用说明](https://help.sap.com/docs/SAP_HANA_PLATFORM/3823b0f33420468ba5f1cf7f59bd6bd9/6df24edbef3a4e039a7b0a7bf4acd6c8.html)。只启动计算视图必需的 diserver，不启动 scriptserver、dpserver 或 XS 应用服务。

测试使用 SYSTEM 创建名称随机且全大写的 HDI 容器，再通过 HDI 自身的 API 授予部署连接管理权限与 SELECT/INSERT/EXECUTE schema 权限，用于建立和填充 fixture。授权对象只限本次容器。

运行时权限使用另一名新建的非 SYSTEM 用户单独验证：通过 GRANT_CONTAINER_SCHEMA_PRIVILEGES 仅授予该容器的 SELECT/EXECUTE，没有授予部署 API 或写入权限。确认 CURRENT_USER 后查询标量和多值计算视图，以及带默认值/NULL 的表函数，再验证 CTE/count/page；UPDATE 返回 HANA 258。表函数明确为 SQL SECURITY INVOKER，因此底表 SELECT 与函数 EXECUTE 都需要，不能误称只有 EXECUTE 就足够。用户在 finally 删除，随后销毁容器；正式应用的授权应按自己的模型和安全模式安排，不将测试管理员当成应用身份。

## 验证内容

`HanaHdiIT` 上传六个自有测试工件：

- `.hdiconfig`：只注册 table、calculationview 与 function 构建插件。
- `EQP_SOURCE.hdbtable`：带主键、Unicode 名称、定点金额的源表。
- `EQP_CALC.hdbcalculationview`：真正的 Calculation Scenario XML，使用 NVARCHAR 输入参数 `P_NAME` 过滤源表。
- `EQP_CALC_NAMES.hdbcalculationview`：NVARCHAR 多值模型，验证引号、逗号、中文、空字符串和注入样式文本的精确匹配。
- `EQP_CALC_IDS.hdbcalculationview`：INTEGER 多值模型，验证数值列表作为一个 JDBC 参数绑定。
- `EQP_FUNCTION.hdbfunction`：具有三个标量输入及默认值的 SQLScript 表函数，验证命名绑定、缺省中间参数与显式 NULL。

部署走 HDI `WRITE` / `MAKE`，JDBC CallableStatement 读取返回码和消息表。SQL 调用没有抛异常不等于 HDI 部署成功，必须验证返回码为 0。使用顶层 CALL 直接传入临时参数表，避免 SQLScript 嵌套调用对临时表和表变量的限制。

验证通过 `PLACEHOLDER."$$P_NAME$$" => ?` 绑定值，并核对带单引号和中文的结果、金额精度及额外行过滤。检查系统目录中的视图类型，确保工件是计算视图。类型化 HanaCalculationView 再复用同一已部署模型，验证外层筛选、Draft、CTE、聚合、独立 JOIN、count/page，以及参数值与外层条件的绑定顺序。最后在 finally 中删除本次随机容器，包括已部署内容；临时表随专用连接关闭清理。测试不会修改或删除已有业务 schema。

```powershell
.\tools\hana.ps1 -Action start
.\tools\hana.ps1 -Action test -IntegrationTest 'HanaHdiIT'
```

## 参考边界

XML 结构和输入参数写法参考 SAP 官方示例仓库 [hana-shine-xsa](https://github.com/SAP-samples/hana-shine-xsa/tree/0cfd539e629edee37942ee3acc54de25f811c9d3)，固定 commit 为 `0cfd539e629edee37942ee3acc54de25f811c9d3`，查看了 `core-db/src/models/PROD.hdbcalculationview`、`SALES_YEAR_COMPARISON.hdbcalculationview` 和 `.hdiconfig`。本项目使用独立、最小的三列测试模型，不引入示例的业务结构、CDS、XSA 或运行依赖。

标量与多值计算视图、标量输入表函数及其泛型/输入校验已经接入该 fixture，API 见 [HANA 查询来源](hana-query-sources.md)。默认值 XML 使用同一固定 SAP 样例中的 `defaultRange lowValue` 形态，表函数使用 `.hdbfunction` 与 SQLScript DEFAULT。两个入口共享安全绑定基础并分别验证查询、JOIN、CTE、聚合、分页和原 client 事务。独立运行时用户的 SELECT/EXECUTE 已有实际证据，其他模型权限、精度及完整产物消费继续按 H11/G6 验收，单个 fixture 通过不代表全部完成。
