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
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

/**
 * 显式声明数组元素类型与 SQL 存储类型，并用 PgArrayValue 携带运行时元素信息。
 * Java 泛型擦除后仅有 List.class 不足以确定数组 OID，因此不注册全局 List/Object[] handler。
 * 当前支持的元素族集中在 TYPES 中；新增族需同时确认元素绑定、读取和数组形状契约。
 */
public final class PgArrayType<E> implements StorageType<PgArrayValue<E>>, CastTarget<PgArrayValue<E>> {
    private static final TimestampUtils TIMESTAMPS = new TimestampUtils(false, () -> TimeZone.getTimeZone("UTC"));
    private static final Map<Class<?>, String> TYPES = ImmutableCollections.<Class<?>, String>mapBuilder()
            .put(String.class, "text").put(Integer.class, "int4").put(Long.class, "int8").put(BigDecimal.class, "numeric")
            .put(Boolean.class, "bool").put(UUID.class, "uuid").put(LocalDate.class, "date").put(LocalDateTime.class, "timestamp").build();
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
        for (Map.Entry<Class<?>, String> entry : TYPES.entrySet()) {
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

    /**
     * null 表示 SQL NULL；空 List 表示空数组；含 null 元素的 List 保留元素 NULL，三者不混淆。
     */
    public PgArrayValue<E> value(List<? extends E> elements) {
        return elements == null ? null : new PgArrayValue<>(this, elements);
    }

    public E prepareElement(E value) {
        if (value != null && !elementType.isInstance(value)) {
            throw new IllegalArgumentException("Expected " + elementType.getTypeName() + " array element, found " + value.getClass().getTypeName());
        }
        if (value instanceof String) UnicodeStrings.requirePortable(((String) value), "Array element");
        return value;
    }

    Object jdbcElement(Object value) {
        // 标量和数组参数统一使用驱动的纪元、infinity 和微秒规则，避免自行拼日期造成语义偏差。
        if (value instanceof LocalDate) return TIMESTAMPS.toString(((LocalDate) value));
        if (value instanceof LocalDateTime) return TIMESTAMPS.toString(((LocalDateTime) value));
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
        return other instanceof PgArrayType<?> && elementType.equals(((PgArrayType<?>) other).elementType);
    }

    @Override
    public int hashCode() {
        return elementType.hashCode();
    }
}
