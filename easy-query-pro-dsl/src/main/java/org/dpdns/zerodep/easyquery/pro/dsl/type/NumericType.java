package org.dpdns.zerodep.easyquery.pro.dsl.type;

/**
 * Numeric targets retain their Java result type and use the existing numeric expression API.
 */
public interface NumericType<N extends Number> extends CastTarget<N>, StorageType<N> {
}
