package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;

public final class MySqlTypes {
    private static final Json JSON = new Json();

    private MySqlTypes() {
    }

    public static Json json() {
        return JSON;
    }

    public static final class Json extends NativeJsonType {
        private Json() {
            super(SqlDialect.MYSQL, "JSON");
        }

        @Override
        public String castTemplate(SqlDialect actual) {
            requireDialect(actual);
            // Client-side preparation with NO_BACKSLASH_ESCAPES can bind strings as binary hex.
            return "CAST(CONVERT({0} USING utf8mb4) AS JSON)";
        }
    }
}
