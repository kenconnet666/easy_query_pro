package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.PgArrayRecord;
import org.junit.jupiter.api.Test;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;

import static org.junit.jupiter.api.Assertions.*;

class PgArrayDslTest {
    @Test
    void arrayViewsRejectContradictoryDeclarationsAndInvalidDimensions() {
        DefaultEasyEntityQuery client = client();
        assertThrows(IllegalArgumentException.class, () -> client.queryable(PgArrayRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(String.class))));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(PgArrayRecord.class).selectColumn(p -> PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(Integer.class)).dimensionLength(0)));
        assertDoesNotThrow(() -> client.queryable(PgArrayRecord.class).selectColumn(p -> PgSqlDsl.of(p).nativeCast(p.numbers(), PgSqlTypes.array(Long.class))).toSQL());
        DefaultEasyEntityQuery other = client();
        client.queryable(PgArrayRecord.class).where(p -> other.queryable(PgArrayRecord.class).where(q ->
                assertThrows(IllegalArgumentException.class, () -> PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(Integer.class))
                        .overlaps(PgSqlDsl.of(q).view(q.numbers(), PgSqlTypes.array(Integer.class))))));
    }

    private static DefaultEasyEntityQuery client() {
        DefaultEasyEntityQuery client = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        JdbcTypeHandlerManager handlers = client.getRuntimeContext().getJdbcTypeHandlerManager();
        JdbcTypeHandler original = handlers.getHandler(String.class);
        PgSqlExtensions.configureTypeHandlers(handlers);
        assertSame(original, handlers.getHandler(String.class));
        client.getRuntimeContext().getQueryConfiguration().applyValueConverter(new PgArrayRecord.LabelsConverter());
        return client;
    }
}
