# HANA HDI 与真实计算视图测试

目标是验证 HANA 2.0 本地部署的计算视图能力，不用普通 SQL view 或表函数替代计算视图。

## 服务与权限

官方 Express 镜像中的 SYSTEMDB 使用 39017，HXE 租户使用 39041。数据库服务版没有为 HXE 自动启用 HDI，需要在 SYSTEMDB 添加租户的 diserver。`infra/hana/enable-hdi.sql` 只针对固定的测试租户 HXE，已存在时不重复添加；配套 shell 检查 HXE 的 `_SYS_DI.CREATE_CONTAINER` API 是否就绪。本地 `tools/hana.ps1 start` 和 HANA CI job 都执行同一入口。

这一步来自 [SAP HANA Platform 的租户 HDI 启用说明](https://help.sap.com/docs/SAP_HANA_PLATFORM/3823b0f33420468ba5f1cf7f59bd6bd9/6df24edbef3a4e039a7b0a7bf4acd6c8.html)。只启动计算视图必需的 diserver，不启动 scriptserver、dpserver 或 XS 应用服务。

测试使用 SYSTEM 创建名称随机且全大写的 HDI 容器，再通过 HDI 自身的 API 授予该容器的管理权限与 SELECT/INSERT schema 权限。授权对象只限本次容器。正式应用的非 SYSTEM 权限验证仍由 G3.3/G6 完成，不能用这里的管理凭据测试替代。

## 验证内容

`HanaHdiIT` 上传三个自有测试工件：

- `.hdiconfig`：只注册 table 与 calculationview 构建插件。
- `EQP_SOURCE.hdbtable`：带主键、Unicode 名称、定点金额的源表。
- `EQP_CALC.hdbcalculationview`：真正的 Calculation Scenario XML，使用 NVARCHAR 输入参数 `P_NAME` 过滤源表。

部署走 HDI `WRITE` / `MAKE`，JDBC CallableStatement 读取返回码和消息表。SQL 调用没有抛异常不等于 HDI 部署成功，必须验证返回码为 0。使用顶层 CALL 直接传入临时参数表，避免 SQLScript 嵌套调用对临时表和表变量的限制。

验证通过 `PLACEHOLDER."$$P_NAME$$" => ?` 绑定值，并核对带单引号和中文的结果、金额精度及额外行过滤。检查系统目录中的视图类型，确保工件是计算视图。最后在 finally 中删除本次随机容器，包括已部署内容；临时表随专用连接关闭清理。测试不会修改或删除已有业务 schema。

```powershell
.\tools\hana.ps1 -Action start
.\tools\hana.ps1 -Action test -IntegrationTest 'HanaHdiIT'
```

## 参考边界

XML 结构和输入参数写法参考 SAP 官方示例仓库 [hana-shine-xsa](https://github.com/SAP-samples/hana-shine-xsa/tree/0cfd539e629edee37942ee3acc54de25f811c9d3)，固定 commit 为 `0cfd539e629edee37942ee3acc54de25f811c9d3`，查看了 `core-db/src/models/PROD.hdbcalculationview`、`SALES_YEAR_COMPARISON.hdbcalculationview` 和 `.hdiconfig`。本项目使用独立、最小的三列测试模型，不引入示例的业务结构、CDS、XSA 或运行依赖。

本测试建立的是 G0/G5 可复用的真实数据库验证条件。正式计算视图 DSL 的类型、参数、组合查询与错误诊断仍需要在此基础上实现和验收。
