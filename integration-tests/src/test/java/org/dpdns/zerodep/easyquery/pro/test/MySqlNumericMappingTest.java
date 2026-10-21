package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.types.EasyJdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.handler.FloatTypeHandler;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.junit.jupiter.api.Test;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;

import static org.junit.jupiter.api.Assertions.*;

class MySqlNumericMappingTest {
    @Test
    void explicitLegacyFloatHandlerAndPrimitiveUseTheInstalledDefault() {
        DefaultEasyEntityQuery client = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        JdbcTypeHandlerManager handlers = client.getRuntimeContext().getJdbcTypeHandlerManager();
        assertSame(handlers.getHandler(Float.class), handlers.getHandler(float.class));
        assertSame(handlers.getHandler(Float.class), handlers.getHandlerByHandlerClass(FloatTypeHandler.class));
        assertNotEquals(FloatTypeHandler.class, handlers.getHandler(Float.class).getClass());
    }

    @Test
    void applicationHandlersAndExplicitSubclassNamesRemainAuthoritative() {
        DefaultEasyEntityQuery client = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        JdbcTypeHandlerManager handlers = client.getRuntimeContext().getJdbcTypeHandlerManager();
        JdbcTypeHandler standard = handlers.getHandlerByHandlerClass(FloatTypeHandler.class);
        MySqlNumericMappingTest.ApplicationFloat custom = new ApplicationFloat();
        handlers.appendHandler(Float.class, custom, true);
        MySqlExtensions.configureTypeHandlers(handlers);
        assertSame(custom, handlers.getHandler(Float.class));
        assertSame(custom, handlers.getHandlerByHandlerClass(ApplicationFloat.class));
        assertSame(standard, handlers.getHandlerByHandlerClass(FloatTypeHandler.class));
    }

    @Test
    void applicationManagerInstalledAfterExtensionsIsNotReplacedLater() {
        EasyJdbcTypeHandlerManager custom = new EasyJdbcTypeHandlerManager();
        MySqlNumericMappingTest.ApplicationFloat handler = new ApplicationFloat();
        custom.appendHandler(Float.class, handler, true);
        DefaultEasyEntityQuery client = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            services.addService(JdbcTypeHandlerManager.class, custom);
        });
        MySqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
        assertSame(custom, client.getRuntimeContext().getJdbcTypeHandlerManager());
        assertSame(handler, custom.getHandler(Float.class));
    }

    @Test
    void explicitLegacyClassUsesAnApplicationRegisteredInstance() {
        DefaultEasyEntityQuery client = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        JdbcTypeHandlerManager handlers = client.getRuntimeContext().getJdbcTypeHandlerManager();
        FloatTypeHandler custom = new FloatTypeHandler();
        handlers.appendHandler(Float.class, custom, true);
        assertSame(custom, handlers.getHandlerByHandlerClass(FloatTypeHandler.class));
    }

    public static final class ApplicationFloat extends FloatTypeHandler {
    }
}
