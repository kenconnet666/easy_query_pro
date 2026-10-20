package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;

/**
 * Single-value JSON navigation; structural mismatches produce SQL NULL.
 */
public interface JsonPathAccess {
    default void requireNavigationSupport() {
    }

    SQLFunction fragment(SQLFunc factory, SQLFunction document, JsonPath path);

    SQLFunction scalarText(SQLFunc factory, SQLFunction fragment);

    default SQLFunction serializedText(SQLFunc factory, SQLFunction fragment) {
        return factory.cast(fragment, String.class);
    }
}
