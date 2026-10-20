package org.dpdns.zerodep.easyquery.pro.dsl.hana.lob;

import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;

public interface HanaTextLobExpression<T> extends HanaLobExpression<T> {
    @Override
    HanaTypes.TextLob sqlType();

    NumberTypeExpression<Long> utf16Length();

    /**
     * Native one-based UTF-16 indexing; boundaries can split surrogate pairs. Length is limited to 0..5000.
     */
    StringTypeExpression<String> sliceUtf16(int start, int length);

    /**
     * Explicit scalar conversion, preserving complete text rather than imposing a 5000-unit CAST limit.
     */
    StringTypeExpression<String> asText();
}
