package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CastTarget;
import org.dpdns.zerodep.easyquery.pro.dsl.type.StorageType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.TimeZone;

import org.postgresql.jdbc.TimestampUtils;

/**
 * Element type and array storage are explicit; no global List or Object[] mapping is installed.
 */
public final class PgArrayType<E> implements StorageType<PgArrayValue<E>>, CastTarget<PgArrayValue<E>> {
    private static final TimestampUtils TIMESTAMPS = new TimestampUtils(false, () -> TimeZone.getTimeZone("UTC"));
    private static final Map<Class<?>, String> TYPES = Map.of(String.class, "text", Integer.class, "int4", Long.class, "int8",
            BigDecimal.class, "numeric", Boolean.class, "bool", UUID.class, "uuid", LocalDate.class, "date", LocalDateTime.class, "timestamp");
    private final Class<E> elementType;
    private final String elementSqlName;

    private PgArrayType(Class<E> elementType, String elementSqlName) {
        this.elementType = elementType;
        this.elementSqlName = elementSqlName;
    }

    public static <E> PgArrayType<E> of(Class<E> elementType) {
        String name = TYPES.get(Objects.requireNonNull(elementType, "elementType"));
        if (name == null)
            throw new IllegalArgumentException("Unsupported PostgreSQL array element type: " + elementType.getTypeName());
        return new PgArrayType<>(elementType, name);
    }

    static PgArrayType<?> fromJdbcName(String name) {
        String canonical = name.toLowerCase(Locale.ROOT);
        for (var entry : TYPES.entrySet()) {
            if (entry.getValue().equals(canonical)) return of(entry.getKey());
        }
        throw new IllegalArgumentException("Unsupported PostgreSQL array element SQL type: " + name);
    }

    public Class<E> elementType() {
        return elementType;
    }

    public String elementSqlName() {
        return elementSqlName;
    }

    public String sqlName() {
        return elementSqlName + "[]";
    }

    public PgArrayValue<E> value(List<? extends E> elements) {
        return elements == null ? null : new PgArrayValue<>(this, elements);
    }

    public E prepareElement(E value) {
        if (value != null && !elementType.isInstance(value)) {
            throw new IllegalArgumentException("Expected " + elementType.getTypeName() + " array element, found " + value.getClass().getTypeName());
        }
        if (value instanceof String text) UnicodeStrings.requirePortable(text, "Array element");
        return value;
    }

    Object jdbcElement(Object value) {
        // Use the driver's era, infinity and microsecond rules for both scalar and array parameters.
        if (value instanceof LocalDate date) return TIMESTAMPS.toString(date);
        if (value instanceof LocalDateTime timestamp) return TIMESTAMPS.toString(timestamp);
        return value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<PgArrayValue<E>> javaType() {
        // The element type is carried by the descriptor and each value, not recoverable from Java's erased Class.
        return (Class<PgArrayValue<E>>) (Class<?>) PgArrayValue.class;
    }

    @Override
    public String ddlType(SqlDialect dialect) {
        if (dialect != SqlDialect.POSTGRESQL)
            throw new IllegalArgumentException("SQL arrays require PostgreSQL; found " + dialect);
        return sqlName();
    }

    @Override
    public String castTemplate(SqlDialect dialect) {
        return "CAST({0} AS " + ddlType(dialect) + ")";
    }

    @Override
    public PgArrayValue<E> prepareValue(PgArrayValue<E> value) {
        if (value != null && !equals(value.type()))
            throw new IllegalArgumentException("Array SQL element type does not match " + sqlName());
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PgArrayType<?> type && elementType.equals(type.elementType);
    }

    @Override
    public int hashCode() {
        return elementType.hashCode();
    }
}
