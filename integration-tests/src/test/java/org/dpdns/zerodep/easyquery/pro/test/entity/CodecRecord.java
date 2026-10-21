package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.basic.extension.conversion.ColumnValueSQLConverter;
import com.easy.query.core.basic.extension.conversion.SQLPropertyConverter;
import com.easy.query.core.basic.extension.conversion.ValueConverter;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.basic.jdbc.types.handler.LongTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.StringTypeHandler;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.parser.core.base.SimpleSQLTableOwner;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.CodecRecordProxy;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Objects;

@Table("eqp_codec")
@EntityProxy
public class CodecRecord implements ProxyEntityAvailable<CodecRecord, CodecRecordProxy> {
    @Column(primaryKey = true, generatedKey = true, dbType = "BIGINT", conversion = IdConverter.class, typeHandler = LongTypeHandler.class)
    private RowId id;
    @Column(dbType = "NVARCHAR(512)", conversion = LabelConverter.class, typeHandler = StringTypeHandler.class)
    private Label label;
    @Column(dbType = "NCLOB", conversion = LabelConverter.class, typeHandler = StringTypeHandler.class)
    private Label document;
    @Column(dbType = "DECIMAL(19,0)", sqlConversion = CentsConverter.class)
    private BigDecimal price;
    private String plain;

    public RowId getId() {
        return id;
    }

    public void setId(RowId id) {
        this.id = id;
    }

    public Label getLabel() {
        return label;
    }

    public void setLabel(Label label) {
        this.label = label;
    }

    public Label getDocument() {
        return document;
    }

    public void setDocument(Label document) {
        this.document = document;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getPlain() {
        return plain;
    }

    public void setPlain(String plain) {
        this.plain = plain;
    }

    public static final class RowId {
        private final long value;

        public RowId(long value) {
            this.value = value;
        }

        public long value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RowId)) return false;
            RowId other = (RowId) o;
            return value == other.value;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }

        @Override
        public String toString() {
            return "RowId[value=" + value + "]";
        }
    }

    public static final class Label {
        private final String value;

        public Label(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Label)) return false;
            Label other = (Label) o;
            return Objects.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }

        @Override
        public String toString() {
            return "Label[value=" + value + "]";
        }
    }

    public static final class IdConverter implements ValueConverter<RowId, Long> {
        @Override
        public Long serialize(RowId value, @NotNull ColumnMetadata column) {
            return value == null ? null : value.value();
        }

        @Override
        public RowId deserialize(Long value, @NotNull ColumnMetadata column) {
            return value == null ? null : new RowId(value);
        }
    }

    public static final class LabelConverter implements ValueConverter<Label, String> {
        private final String prefix;

        public LabelConverter(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String serialize(Label value, @NotNull ColumnMetadata column) {
            return value == null ? null : prefix + value.value();
        }

        @Override
        public Label deserialize(String value, @NotNull ColumnMetadata column) {
            if (value == null) return null;
            if (!value.startsWith(prefix)) throw new IllegalArgumentException("Unexpected encoded label");
            return new Label(value.substring(prefix.length()));
        }
    }

    public static final class CentsConverter implements ColumnValueSQLConverter {
        @Override
        public boolean isRealColumn() {
            return true;
        }

        @Override
        public void selectColumnConvert(@NotNull TableAvailable table, @NotNull ColumnMetadata column,
                                        @NotNull SQLPropertyConverter converter, @NotNull QueryRuntimeContext runtime) {
            converter.sqlNativeSegment("({0}/100)", sql -> {
                sql.expression(new SimpleSQLTableOwner(table), column.getPropertyName());
                sql.setAlias(column.getName());
            });
        }

        @Override
        public void propertyColumnConvert(@NotNull TableAvailable table, @NotNull ColumnMetadata column,
                                          @NotNull SQLPropertyConverter converter, @NotNull QueryRuntimeContext runtime) {
            converter.sqlNativeSegment("({0}/100)", sql -> sql.expression(new SimpleSQLTableOwner(table), column.getPropertyName()));
        }

        @Override
        public void valueConvert(@NotNull TableAvailable table, @NotNull ColumnMetadata column, @NotNull SQLParameter value,
                                 @NotNull SQLPropertyConverter converter, @NotNull QueryRuntimeContext runtime, boolean comparison) {
            // HANA 会按目标列推断参数 scale；转换前明确输入精度，避免 19.99 被先截成 19。
            converter.sqlNativeSegment(comparison ? "{0}" : "(CAST({0} AS DECIMAL(19,2))*100)", sql -> sql.value(value));
        }
    }

    public static final class View {
        @Column(conversion = IdConverter.class, typeHandler = LongTypeHandler.class)
        private RowId id;
        @Column(conversion = LabelConverter.class, typeHandler = StringTypeHandler.class)
        private Label label;

        public RowId getId() {
            return id;
        }

        public void setId(RowId id) {
            this.id = id;
        }

        public Label getLabel() {
            return label;
        }

        public void setLabel(Label label) {
            this.label = label;
        }
    }
}
