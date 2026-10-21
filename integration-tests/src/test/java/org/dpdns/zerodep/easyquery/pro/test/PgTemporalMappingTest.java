package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.types.handler.LocalDateTimeTypeHandler;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import com.easy.query.pgsql.types.PgSQLUUIDTypeHandler;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;

import static org.junit.jupiter.api.Assertions.*;

class PgTemporalMappingTest {
    @Test
    void legacyHandlerNamesAndPostgresUuidRemainAvailable() {
        DefaultEasyEntityQuery query = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        JdbcTypeHandlerManager handlers = query.getRuntimeContext().getJdbcTypeHandlerManager();
        assertSame(PgSQLUUIDTypeHandler.INSTANCE, handlers.getHandler(UUID.class));
        assertSame(handlers.getHandler(LocalDateTime.class), handlers.getHandlerByHandlerClass(LocalDateTimeTypeHandler.class));
        assertNotEquals(LocalDateTimeTypeHandler.class, handlers.getHandler(LocalDateTime.class).getClass());
    }

    @Test
    void customHandlersAndTheirExactClassNamesKeepPriority() {
        DefaultEasyEntityQuery query = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        JdbcTypeHandlerManager handlers = query.getRuntimeContext().getJdbcTypeHandlerManager();
        JdbcTypeHandler standard = handlers.getHandlerByHandlerClass(LocalDateTimeTypeHandler.class);
        PgTemporalMappingTest.CustomDateTime custom = new CustomDateTime();
        handlers.appendHandler(LocalDateTime.class, custom, true);
        PgSqlExtensions.configureTypeHandlers(handlers);
        assertSame(custom, handlers.getHandler(LocalDateTime.class));
        assertSame(custom, handlers.getHandlerByHandlerClass(CustomDateTime.class));
        // 显式指定上游默认类名仍表示默认映射，不误截获自定义子类的独立声明。
        assertSame(standard, handlers.getHandlerByHandlerClass(LocalDateTimeTypeHandler.class));
    }

    @Test
    void explicitLegacyClassUsesAnApplicationRegisteredInstance() {
        DefaultEasyEntityQuery query = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        JdbcTypeHandlerManager handlers = query.getRuntimeContext().getJdbcTypeHandlerManager();
        LocalDateTimeTypeHandler custom = new LocalDateTimeTypeHandler();
        handlers.appendHandler(LocalDateTime.class, custom, true);
        assertSame(custom, handlers.getHandlerByHandlerClass(LocalDateTimeTypeHandler.class));
    }

    public static final class CustomDateTime extends LocalDateTimeTypeHandler {
    }
}
