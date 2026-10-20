package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 不可变的一维 SQL 数组值，携带元素类型及默认下界 1，保留 NULL 元素。
 * 构造时复制元素列表并按 PgArrayType 校验；不能只凭 Java Class 的泛型擦除推断 SQL 元素类型。
 * 非一维或非默认下界的数据库数组在读取阶段明确拒绝，避免转换成 List 后静默丢失形状信息。
 */
public final class PgArrayValue<E> {
    private final PgArrayType<E> type;
    private final List<E> elements;

    PgArrayValue(PgArrayType<E> type, List<? extends E> elements) {
        this.type = type;
        var copy = new ArrayList<E>(elements.size());
        for (E value : elements) copy.add(type.prepareElement(value));
        this.elements = Collections.unmodifiableList(copy);
    }

    public PgArrayType<E> type() {
        return type;
    }

    public List<E> elements() {
        return elements;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PgArrayValue<?> value && type.equals(value.type) && elements.equals(value.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, elements);
    }

    @Override
    public String toString() {
        return type.sqlName() + elements;
    }
}
