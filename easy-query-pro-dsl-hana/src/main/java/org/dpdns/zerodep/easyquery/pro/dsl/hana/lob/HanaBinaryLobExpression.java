package org.dpdns.zerodep.easyquery.pro.dsl.hana.lob;

import com.easy.query.core.proxy.extension.functions.type.AnyTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;

public interface HanaBinaryLobExpression<T> extends HanaLobExpression<T> {
    @Override
    HanaTypes.BinaryLob sqlType();

    NumberTypeExpression<Long> byteLength();

    /**
     * Native one-based byte indexing. Length is limited to 0..5000 for a bounded scalar result.
     */
    AnyTypeExpression<byte[]> sliceBytes(int start, int length);
}
