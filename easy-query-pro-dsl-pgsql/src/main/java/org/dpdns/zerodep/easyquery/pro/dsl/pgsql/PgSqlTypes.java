package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;

public final class PgSqlTypes {
    private static final Json JSON = new Json();
    private static final Jsonb JSONB = new Jsonb();

    private PgSqlTypes() {
    }

    public static Json json() {
        return JSON;
    }

    public static Jsonb jsonb() {
        return JSONB;
    }

    public static final class Json extends NativeJsonType {
        private Json() {
            super(SqlDialect.POSTGRESQL, "JSON");
        }
    }

    public static final class Jsonb extends NativeJsonType {
        private Jsonb() {
            super(SqlDialect.POSTGRESQL, "JSONB");
        }
    }
}
