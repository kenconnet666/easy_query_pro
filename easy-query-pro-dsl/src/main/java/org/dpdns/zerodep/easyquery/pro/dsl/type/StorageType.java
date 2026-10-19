package org.dpdns.zerodep.easyquery.pro.dsl.type;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

/**
 * A DDL declaration; storage support does not itself grant CAST or expression operations.
 */
public interface StorageType<T> {
    Class<T> javaType();

    String ddlType(SqlDialect dialect);
}
