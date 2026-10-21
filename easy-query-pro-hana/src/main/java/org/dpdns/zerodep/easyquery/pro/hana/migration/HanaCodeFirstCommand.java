package org.dpdns.zerodep.easyquery.pro.hana.migration;

import com.easy.query.core.basic.api.database.CodeFirstCommand;
import com.easy.query.core.basic.api.database.CodeFirstCommandArg;
import com.easy.query.core.basic.api.database.CodeFirstCommandTxArg;
import com.easy.query.core.basic.api.database.DefaultCodeFirstCommandArg;
import com.easy.query.core.basic.api.database.DefaultCodeFirstCommandTxArg;
import com.easy.query.core.basic.jdbc.conn.EasyConnection;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.enums.conn.ConnectionStrategyEnum;
import com.easy.query.core.exception.EasyQuerySQLCommandException;
import com.easy.query.core.migration.MigrationCommand;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaEasyConnection;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import com.easy.query.core.util.EasyStringUtil;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import com.easy.query.core.basic.jdbc.conn.ConnectionManager;
import com.easy.query.core.basic.api.jdbc.JdbcExecutor;

/**
 * 执行已经划分好语句边界的 HANA 迁移命令，并区分自有事务和环境事务。
 *
 * <p>自有事务在执行前进入专用 DDL 会话范围，由 HanaEasyConnection 在提交、回滚及归还时恢复模式。
 * 已有环境事务只能校验 AUTOCOMMIT DDL 已关闭，不能替调用方切换模式，因为切换本身可能隐式提交
 * 先前的 DML。这里只承诺受控路径的事务规则，不把任意 HANA DDL 都视为可安全回滚。
 *
 * <p>preview 用于展示和回调，执行使用独立 statements。不能按分号重新拆分展示文本，
 * 否则注释、默认值或单条原生命令中的分号会破坏语句边界。
 */
final class HanaCodeFirstCommand implements CodeFirstCommand {
    private final QueryRuntimeContext context;
    private final List<String> statements;
    private final String preview;

    HanaCodeFirstCommand(QueryRuntimeContext context, List<MigrationCommand> commands) {
        this.context = context;
        ArrayList<String> sql = new ArrayList<String>();
        ArrayList<String> previews = new ArrayList<String>();
        for (MigrationCommand command : commands) {
            String rendered = command.toSQL();
            if (EasyStringUtil.isBlank(rendered)) continue;
            previews.add(rendered);
            if (command instanceof HanaMigrationCommand) sql.addAll(((HanaMigrationCommand) command).statements());
            else sql.add(rendered);
        }
        statements = ImmutableCollections.listCopyOf(sql);
        preview = String.join(System.lineSeparator(), previews);
    }

    @Override
    public void executeWithTransaction(Consumer<CodeFirstCommandTxArg> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (statements.isEmpty()) return;
        try (Transaction transaction = context.getConnectionManager().beginTransaction()) {
            // 此连接由事务拥有；不能用局部 try-with-resources 提前关闭。
            EasyConnection easy = connection();
            if (!(easy instanceof HanaEasyConnection)) {
                throw new IllegalStateException("Owned HANA DDL transactions require HanaConnectionFactory; externally managed transactions use executeWithEnvTransaction");
            }
            HanaEasyConnection hana = (HanaEasyConnection) easy;
            hana.beginDdlScope();
            DefaultCodeFirstCommandTxArg decision = new DefaultCodeFirstCommandTxArg(preview);
            consumer.accept(decision);
            execute();
            decision.getTransactionConsumer().accept(transaction);
        } catch (SQLException failure) {
            throw new EasyQuerySQLCommandException("Unable to initialize HANA DDL transaction", failure);
        }
    }

    /**
     * 环境事务存在时保留其所有权；不存在时转为自有事务并按此入口语义提交。
     */
    @Override
    public void executeWithEnvTransaction(Consumer<CodeFirstCommandArg> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        ConnectionManager manager = context.getConnectionManager();
        if (!manager.currentThreadInTransaction()) {
            executeWithTransaction(arg -> {
                consumer.accept(new DefaultCodeFirstCommandArg(arg.getSQL()));
                arg.commit();
            });
            return;
        }
        if (statements.isEmpty()) return;
        EasyConnection easy = connection();
        try {
            HanaEasyConnection.requireTransactionalDdl(easy.getConnection());
            consumer.accept(new DefaultCodeFirstCommandArg(preview));
            execute();
        } catch (SQLException failure) {
            throw new EasyQuerySQLCommandException("HANA migration requires an existing transaction with AUTOCOMMIT DDL OFF", failure);
        } finally {
            manager.closeEasyConnection(easy);
        }
    }

    private EasyConnection connection() {
        return context.getConnectionManager().getEasyConnections(1, context.getDataSourceManager().getDefaultDataSourceName(),
                ConnectionStrategyEnum.ShareConnection).get(0);
    }

    private void execute() {
        JdbcExecutor executor = context.getSQLClientApiFactory().createJdbcExecutor(context);
        for (int index = 0; index < statements.size(); index++) {
            try {
                executor.sqlExecute(statements.get(index), ImmutableCollections.listOf());
            } catch (RuntimeException failure) {
                throw new EasyQuerySQLCommandException("HANA migration statement " + (index + 1) + "/" + statements.size() + " failed", failure);
            }
        }
    }
}
