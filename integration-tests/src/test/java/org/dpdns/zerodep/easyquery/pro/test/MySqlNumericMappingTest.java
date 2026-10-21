package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.types.EasyJdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.handler.FloatTypeHandler;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MySqlNumericMappingTest {
    @Test
    void explicitLegacyFloatHandlerAndPrimitiveUseTheInstalledDefault() {
        var client = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        var handlers = client.getRuntimeContext().getJdbcTypeHandlerManager();
        assertSame(handlers.getHandler(Float.class), handlers.getHandler(float.class));
        assertSame(handlers.getHandler(Float.class), handlers.getHandlerByHandlerClass(FloatTypeHandler.class));
        assertNotEquals(FloatTypeHandler.class, handlers.getHandler(Float.class).getClass());
    }

    @Test
    void applicationHandlersAndExplicitSubclassNamesRemainAuthoritative() {
        var client = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        var handlers = client.getRuntimeContext().getJdbcTypeHandlerManager();
        var standard = handlers.getHandlerByHandlerClass(FloatTypeHandler.class);
        var custom = new ApplicationFloat();
        handlers.appendHandler(Float.class, custom, true);
        MySqlExtensions.configureTypeHandlers(handlers);
        assertSame(custom, handlers.getHandler(Float.class));
        assertSame(custom, handlers.getHandlerByHandlerClass(ApplicationFloat.class));
        assertSame(standard, handlers.getHandlerByHandlerClass(FloatTypeHandler.class));
    }

    @Test
    void applicationManagerInstalledAfterExtensionsIsNotReplacedLater() {
        var custom = new EasyJdbcTypeHandlerManager();
        var handler = new ApplicationFloat();
        custom.appendHandler(Float.class, handler, true);
        var client = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            services.addService(JdbcTypeHandlerManager.class, custom);
        });
        MySqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
        assertSame(custom, client.getRuntimeContext().getJdbcTypeHandlerManager());
        assertSame(handler, custom.getHandler(Float.class));
    }

    @Test
    void explicitLegacyClassUsesAnApplicationRegisteredInstance() {
        var client = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        var handlers = client.getRuntimeContext().getJdbcTypeHandlerManager();
        var custom = new FloatTypeHandler();
        handlers.appendHandler(Float.class, custom, true);
        assertSame(custom, handlers.getHandlerByHandlerClass(FloatTypeHandler.class));
    }

    public static final class ApplicationFloat extends FloatTypeHandler {
    }
}
