# TypeScript ORM 验证记录

## 2026-10-21：基础纵向实现

工程使用 Node 24.18.0、TypeScript 7.0.2、pnpm 11.22.0。核心没有运行时 ORM 依赖；pg 8.23.1、mysql2 3.24.6 通过独立驱动子路径适配。schema/表达式/关系/编译/会话分别维护职责，表定义不自动创建数据库表。

首批已完成：

- 无生成器 schema，Row/Insert/Update 推导，SQL 类型族与 JS 载体分开。
- 不可变查询分叉、INNER/LEFT JOIN、真实独立自连接、显式投影、派生关系、排序/limit/distinct/count。
- client 级具体扩展命名空间、表达式 mount、结构化函数 renderer；不依赖全局 augmentation。
- 显式事务连接、回滚/释放异常保留、提交开始前关闭业务入口。
- PostgreSQL/MySQL 原驱动执行，精确金额和 BIGINT 不先经 JS number。

只读审查给出的重排参数、可空业务映射和派生子类 API 问题，分别在编译结构与类型边界修正。函数 renderer 现返回节点，最终输出顺序决定参数顺序；重复节点会产生对应的重复绑定。派生列保留 T、SQL kind 和 codec，额外子类方法需要重新 mount，避免静态类型承诺不存在的方法。

回归先复现了三个失败：INTEGER 接受超过 32 位的值、withExtensions 重建原 namespace 实例、COMMIT 未完成时逃逸 tx 仍可提交查询。修复后 23 个单元行为用例及类型正负例通过。

两个真实数据库契约在已有本地实例执行：PostgreSQL 17.11、MySQL 8.4.11。覆盖精确 DECIMAL、大于 2^53 的正负 BIGINT、Unicode/引号、LEFT/self JOIN、业务 codec 派生重排、函数参数重排/重复、分页后 count、limit(0).first、事务内写入可见与回滚。MySQL 刻意开启连接池 decimalNumbers，并在 NO_BACKSLASH_ESCAPES 下验证服务端绑定，仍保持精确值及匹配行为。

MySQL 实测暴露文本 CAST 的 collation 冲突：列默认 utf8mb4_0900_ai_ci 与连接 utf8mb4_general_ci 组合时，CHAR CAST 改变参数的强制转换性。修复位于共同方言参数入口，文本保留裸预处理占位符，未通过修改数据库/连接 collation 隐藏问题。规则依据见 [MySQL coercibility](https://dev.mysql.com/doc/refman/8.4/en/charset-collation-coercibility.html)。

每次实库用例只创建随机 eqts_ 表，finally 删除并关闭本次创建的连接池。持久开发实例未删除或重启。完整 CI 独立配置为四个 Node/OS 检查任务和两个数据库任务，使用既有固定镜像与内存限制；当前候选提交仍需其自己的 CI，不能以本地结果冒充完整流水线通过。

仍未完成：HANA Node 驱动、CTE/UNION、类型化写入 builder、完整聚合/窗口/关系加载、干净消费者和完整性能验收。相应目标继续按 plan.md 推进，当前包保持 private，未发布到 npm。
