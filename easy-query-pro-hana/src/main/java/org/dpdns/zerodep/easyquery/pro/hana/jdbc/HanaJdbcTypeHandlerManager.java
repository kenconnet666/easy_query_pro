package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.types.EasyJdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.handler.ByteTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import org.jetbrains.annotations.NotNull;

public final class HanaJdbcTypeHandlerManager extends EasyJdbcTypeHandlerManager {
    private final HanaByteTypeHandler byteHandler = new HanaByteTypeHandler();

    public HanaJdbcTypeHandlerManager() {
        appendHandler(Byte.class, byteHandler, true);
        appendHandler(byte.class, byteHandler, true);
    }

    @Override
    public @NotNull JdbcTypeHandler getHandlerByHandlerClass(@NotNull Class<?> type) {
        // 替换默认映射后，保留实体中显式引用上游处理器类的配置入口。
        return type == ByteTypeHandler.class ? byteHandler : super.getHandlerByHandlerClass(type);
    }
}
