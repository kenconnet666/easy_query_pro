package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.basic.extension.conversion.ColumnValueSQLConverter;
import com.easy.query.core.basic.extension.conversion.SQLPropertyConverter;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.parser.core.base.SimpleSQLTableOwner;
import com.easy.query.core.metadata.ColumnMetadata;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;
import org.jetbrains.annotations.NotNull;

/**
 * Adds a column-specific SQL type to parameters while preserving normal reads and Java conversion.
 */
public abstract class NativeJsonColumnConverter implements ColumnValueSQLConverter {
    private final NativeJsonType type;

    protected NativeJsonColumnConverter(NativeJsonType type) {
        this.type = type;
    }

    @Override
    public final boolean isRealColumn() {
        return true;
    }

    @Override
    public final void selectColumnConvert(@NotNull TableAvailable table, @NotNull ColumnMetadata column,
                                          @NotNull SQLPropertyConverter converter, @NotNull QueryRuntimeContext runtime) {
        converter.sqlNativeSegment("{0}", args -> {
            args.expression(new SimpleSQLTableOwner(table), column.getPropertyName());
            args.setAlias(column.getName());
        });
    }

    @Override
    public final void propertyColumnConvert(@NotNull TableAvailable table, @NotNull ColumnMetadata column,
                                            @NotNull SQLPropertyConverter converter, @NotNull QueryRuntimeContext runtime) {
        converter.sqlNativeSegment("{0}", args -> args.expression(new SimpleSQLTableOwner(table), column.getPropertyName()));
    }

    @Override
    public final void valueConvert(@NotNull TableAvailable table, @NotNull ColumnMetadata column, @NotNull SQLParameter value,
                                   @NotNull SQLPropertyConverter converter, @NotNull QueryRuntimeContext runtime, boolean comparison) {
        // 3.2.17 写入按序列化后的 Java 值选择 handler；这里用公开列转换 SPI 保留 SQL JSON 类型。
        converter.sqlNativeSegment(type.castTemplate(runtime.getService(SqlDialect.class)), args -> args.value(value));
    }
}
