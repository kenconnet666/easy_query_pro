package org.dpdns.zerodep.easyquery.pro.dsl;

/**
 * Explicitly installed dialect identity; it is never inferred from quotes or Java property types.
 */
public enum SqlDialect {
    MYSQL, POSTGRESQL, HANA
}
