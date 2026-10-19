package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.types.EasyJdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.handler.ByteTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalDateTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalDateTimeTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalTimeTypeHandler;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

public final class HanaJdbcTypeHandlerManager extends EasyJdbcTypeHandlerManager {
    private final Map<Class<?>, JdbcTypeHandler> aliases;

    public HanaJdbcTypeHandlerManager() {
        var byteHandler = new HanaByteTypeHandler();
        var date = new HanaTemporalHandlers.Date();
        var time = new HanaTemporalHandlers.Time();
        var dateTime = new HanaTemporalHandlers.DateTime();
        appendHandler(Byte.class, byteHandler, true);
        appendHandler(byte.class, byteHandler, true);
        appendHandler(LocalDate.class, date, true);
        appendHandler(LocalTime.class, time, true);
        appendHandler(LocalDateTime.class, dateTime, true);
        appendHandler(Instant.class, new HanaTemporalHandlers.UtcInstant(), true);
        aliases = Map.of(ByteTypeHandler.class, byteHandler, LocalDateTypeHandler.class, date,
                LocalTimeTypeHandler.class, time, LocalDateTimeTypeHandler.class, dateTime);
    }

    @Override
    public @NotNull JdbcTypeHandler getHandlerByHandlerClass(@NotNull Class<?> type) {
        // 替换默认映射后，保留实体中显式引用上游处理器类的配置入口。
        var handler = aliases.get(type);
        return handler != null ? handler : super.getHandlerByHandlerClass(type);
    }
}
