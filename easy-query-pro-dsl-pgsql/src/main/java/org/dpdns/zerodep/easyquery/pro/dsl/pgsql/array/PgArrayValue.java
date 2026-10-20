package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, one-dimensional SQL array with the default lower bound of one. NULL elements are retained.
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
