package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.types.handler.BigDecimalTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.BlobTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.ByteTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.CLobTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalDateTimeTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalDateTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalTimeTypeHandler;
import com.easy.query.core.exception.EasyQueryInvalidOperationException;
import org.dpdns.zerodep.easyquery.pro.hana.jdbc.HanaJdbcTypeHandlerManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HanaTypeMappingTest {
    static Stream<Arguments> legacyHandlers() {
        return Stream.of(
                Arguments.of(Byte.class, new ByteTypeHandler()),
                Arguments.of(BigDecimal.class, new BigDecimalTypeHandler()),
                Arguments.of(LocalDate.class, new LocalDateTypeHandler()),
                Arguments.of(LocalTime.class, new LocalTimeTypeHandler()),
                Arguments.of(LocalDateTime.class, new LocalDateTimeTypeHandler()),
                Arguments.of(Blob.class, new BlobTypeHandler()),
                Arguments.of(Clob.class, new CLobTypeHandler()));
    }

    @ParameterizedTest
    @MethodSource("legacyHandlers")
    void explicitLegacyClassUsesAnApplicationRegisteredInstance(Class<?> javaType, JdbcTypeHandler custom) {
        HanaJdbcTypeHandlerManager handlers = new HanaJdbcTypeHandlerManager();
        assertSame(handlers.getHandler(javaType), handlers.getHandlerByHandlerClass(custom.getClass()));
        handlers.appendHandler(javaType, custom, true);
        assertSame(custom, handlers.getHandler(javaType));
        assertSame(custom, handlers.getHandlerByHandlerClass(custom.getClass()));
    }

    @Test
    void customSubclassKeepsItsIdentityAndUnknownClassesStillFail() {
        HanaJdbcTypeHandlerManager handlers = new HanaJdbcTypeHandlerManager();
        JdbcTypeHandler standard = handlers.getHandlerByHandlerClass(ByteTypeHandler.class);
        HanaTypeMappingTest.ApplicationByte custom = new ApplicationByte();
        handlers.appendHandler(Byte.class, custom, true);
        assertSame(custom, handlers.getHandlerByHandlerClass(ApplicationByte.class));
        assertSame(standard, handlers.getHandlerByHandlerClass(ByteTypeHandler.class));
        assertThrows(EasyQueryInvalidOperationException.class,
                () -> handlers.getHandlerByHandlerClass(UnregisteredByte.class));
    }

    public static final class ApplicationByte extends ByteTypeHandler {
    }

    public static final class UnregisteredByte extends ByteTypeHandler {
    }
}
