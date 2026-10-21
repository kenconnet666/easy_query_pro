package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.core.proxy.PropTypeColumn;

import java.util.Objects;

/**
 * A window ordering key. SQL NULL ordering follows the selected database.
 */
public final class WindowOrder {
    private final PropTypeColumn<?> column;
    private final boolean ascending;

    public WindowOrder(PropTypeColumn<?> column, boolean ascending) {
        Objects.requireNonNull(column, "column");
        this.column = column;
        this.ascending = ascending;
    }

    public PropTypeColumn<?> column() {
        return column;
    }

    public boolean ascending() {
        return ascending;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WindowOrder)) return false;
        WindowOrder other = (WindowOrder) o;
        return Objects.equals(column, other.column)
                && ascending == other.ascending;
    }

    @Override
    public int hashCode() {
        return Objects.hash(column, ascending);
    }

    @Override
    public String toString() {
        return "WindowOrder[column=" + column + ", ascending=" + ascending + "]";
    }
}
