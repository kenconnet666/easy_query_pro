package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.types.EasyJdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.handler.ByteTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.BigDecimalTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalDateTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalDateTimeTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalTimeTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.BlobTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.CLobTypeHandler;
import com.easy.query.core.exception.EasyQueryInvalidOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Blob;
import java.math.BigDecimal;
import java.sql.Clob;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

public final class HanaJdbcTypeHandlerManager extends EasyJdbcTypeHandlerManager {
    private final Map<Class<?>, JdbcTypeHandler> aliases;
    private final HanaLobBindings lobBindings = new HanaLobBindings();

    public HanaJdbcTypeHandlerManager() {
        var byteHandler = new HanaByteTypeHandler();
        var decimal = new HanaBigDecimalTypeHandler();
        var date = new HanaTemporalHandlers.Date();
        var time = new HanaTemporalHandlers.Time();
        var dateTime = new HanaTemporalHandlers.DateTime();
        var blob = lobBindings.new Binary();
        var clob = lobBindings.new Characters();
        appendHandler(Byte.class, byteHandler, true);
        appendHandler(byte.class, byteHandler, true);
        appendHandler(BigDecimal.class, decimal, true);
        appendHandler(LocalDate.class, date, true);
        appendHandler(LocalTime.class, time, true);
        appendHandler(LocalDateTime.class, dateTime, true);
        appendHandler(Instant.class, new HanaTemporalHandlers.UtcInstant(), true);
        appendHandler(Blob.class, blob, true);
        appendHandler(Clob.class, clob, true);
        aliases = Map.of(ByteTypeHandler.class, byteHandler, LocalDateTypeHandler.class, date,
                LocalTimeTypeHandler.class, time, LocalDateTimeTypeHandler.class, dateTime,
                BlobTypeHandler.class, blob, CLobTypeHandler.class, clob, BigDecimalTypeHandler.class, decimal);
    }

    HanaLobBindings.Scope openLobBindings() {
        return lobBindings.open();
    }

    @Override
    public @NotNull JdbcTypeHandler getHandler(@Nullable Class<?> type) {
        var handler = super.getHandler(type);
        // 上游按具体类查找；SerialClob/驱动 locator 走 setObject 会被 ngdbc 当作 toString() 写入。
        // 仅对未注册的具体类回退到 JDBC 接口，显式自定义 handler 保持优先。
        if (type != null && handler == super.getHandler(null)) {
            if (Blob.class.isAssignableFrom(type)) return super.getHandler(Blob.class);
            if (Clob.class.isAssignableFrom(type)) return super.getHandler(Clob.class);
        }
        return handler;
    }

    @Override
    public @NotNull JdbcTypeHandler getHandlerByHandlerClass(@NotNull Class<?> type) {
        try {
            // 应用显式注册的精确类实例优先；兼容别名只补未注册的旧默认类名。
            return super.getHandlerByHandlerClass(type);
        } catch (EasyQueryInvalidOperationException missing) {
            var handler = aliases.get(type);
            if (handler != null) return handler;
            throw missing;
        }
    }
}
