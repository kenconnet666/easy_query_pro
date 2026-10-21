package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

/**
 * 计算视图多值输入的模型协议编码。多个元素最终是一个 JDBC 字符串，不是 JDBC Array，
 * 也不是用于拼接查询 SQL 的片段。文本元素保留内部逗号并单独转义单引号。
 */
final class HanaListInput<E> extends HanaInput<List<E>> {
    private static final Set<Class<?>> NUMBERS = ImmutableCollections.setOf(Byte.class, Short.class, Integer.class, Long.class, BigInteger.class, BigDecimal.class);
    private final Class<E> elementType;
    private final Function<E, String> encodeElement;

    private HanaListInput(String name, Class<E> elementType, boolean required, Function<E, String> encodeElement) {
        super(name, listClass(), required, false);
        this.elementType = elementType;
        this.encodeElement = encodeElement;
    }

    static HanaInput<List<String>> text(String name, boolean required) {
        return new HanaListInput<>(name, String.class, required, value -> "'" + value.replace("'", "''") + "'");
    }

    static <N extends Number> HanaInput<List<N>> numbers(String name, Class<N> type, boolean required) {
        Objects.requireNonNull(type, "elementType");
        if (!NUMBERS.contains(type))
            throw new IllegalArgumentException("Numeric HANA input lists require an integral wrapper, BigInteger or BigDecimal");
        // 先解析再输出规范十进制，不能把任意 Number.toString 直接当成模型的数值 token。
        return new HanaListInput<>(name, type, required, value -> new BigDecimal(value.toString()).toPlainString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E> Class<List<E>> listClass() {
        // Class 无法表示元素泛型；擦除只封装在这里，validate 必须逐个检查实际元素。
        return (Class) List.class;
    }

    @Override
    protected void validate(List<E> values) {
        super.validate(values);
        if (values.isEmpty())
            throw new IllegalArgumentException("HANA multi-value input " + name() + " cannot be empty; omission and an empty-string element have different meanings");
        for (Object value : values) {
            if (!elementType.isInstance(value))
                throw new IllegalArgumentException("HANA multi-value input " + name() + " requires non-null " + elementType.getName() + " elements");
        }
    }

    @Override
    public HanaInput<List<E>> nullable() {
        // 当前 HANA 2.0 的 IN 多值模型会把 JDBC NULL 展开成 IN ()，它不是一个可用的空集合协议。
        throw new UnsupportedOperationException("HANA multi-value inputs do not support a null list; use an optional declaration to omit the input");
    }

    @Override
    protected Object toJdbcValue(List<E> values) {
        return values.stream().map(encodeElement).collect(Collectors.joining(","));
    }
}
