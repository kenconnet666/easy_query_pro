package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.NativeJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;

public final class PgJsonColumnConverter extends NativeJsonColumnConverter {
    public PgJsonColumnConverter() {
        super(PgSqlTypes.json());
    }
}
