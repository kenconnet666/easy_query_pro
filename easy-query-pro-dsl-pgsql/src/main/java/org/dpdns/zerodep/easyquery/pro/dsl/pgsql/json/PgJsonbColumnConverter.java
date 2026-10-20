package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.NativeJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;

public final class PgJsonbColumnConverter extends NativeJsonColumnConverter {
    public PgJsonbColumnConverter() {
        super(PgSqlTypes.jsonb());
    }
}
