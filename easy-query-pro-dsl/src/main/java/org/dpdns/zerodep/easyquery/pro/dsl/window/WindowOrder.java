package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.core.proxy.PropTypeColumn;

import java.util.Objects;

/**
 * A window ordering key. SQL NULL ordering follows the selected database.
 */
public record WindowOrder(PropTypeColumn<?> column, boolean ascending) {
    public WindowOrder {
        Objects.requireNonNull(column, "column");
    }
}
