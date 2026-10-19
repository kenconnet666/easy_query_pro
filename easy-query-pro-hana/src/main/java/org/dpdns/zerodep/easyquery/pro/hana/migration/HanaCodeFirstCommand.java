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

final class HanaCodeFirstCommand implements CodeFirstCommand {
    private final QueryRuntimeContext context;
    private final List<String> statements;
    private final String preview;

    HanaCodeFirstCommand(QueryRuntimeContext context, List<MigrationCommand> commands) {
        this.context = context;
        var sql = new ArrayList<String>();
        var previews = new ArrayList<String>();
        for (var command : commands) {
            String rendered = command.toSQL();
            if (rendered.isBlank()) continue;
            previews.add(rendered);
            if (command instanceof HanaMigrationCommand hana) sql.addAll(hana.statements());
            else sql.add(rendered);
        }
        statements = List.copyOf(sql);
        preview = String.join(System.lineSeparator(), previews);
    }

    @Override
    public void executeWithTransaction(Consumer<CodeFirstCommandTxArg> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (statements.isEmpty()) return;
        try (var transaction = context.getConnectionManager().beginTransaction()) {
            // 此连接由事务拥有；不能用局部 try-with-resources 提前关闭。
            var easy = connection();
            if (!(easy instanceof HanaEasyConnection hana)) {
                throw new IllegalStateException("Owned HANA DDL transactions require HanaConnectionFactory; externally managed transactions use executeWithEnvTransaction");
            }
            hana.beginDdlScope();
            var decision = new DefaultCodeFirstCommandTxArg(preview);
            consumer.accept(decision);
            execute();
            decision.getTransactionConsumer().accept(transaction);
        } catch (SQLException failure) {
            throw new EasyQuerySQLCommandException("Unable to initialize HANA DDL transaction", failure);
        }
    }

    @Override
    public void executeWithEnvTransaction(Consumer<CodeFirstCommandArg> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        var manager = context.getConnectionManager();
        if (!manager.currentThreadInTransaction()) {
            executeWithTransaction(arg -> {
                consumer.accept(new DefaultCodeFirstCommandArg(arg.getSQL()));
                arg.commit();
            });
            return;
        }
        if (statements.isEmpty()) return;
        var easy = connection();
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
        var executor = context.getSQLClientApiFactory().createJdbcExecutor(context);
        for (int index = 0; index < statements.size(); index++) {
            try {
                executor.sqlExecute(statements.get(index), List.of());
            } catch (RuntimeException failure) {
                throw new EasyQuerySQLCommandException("HANA migration statement " + (index + 1) + "/" + statements.size() + " failed", failure);
            }
        }
    }
}
