package org.dpdns.zerodep.easyquery.pro.dsl.type;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

/**
 * Native SQL JSON storage; separate concrete descriptor classes select different expression APIs.
 */
public abstract class NativeJsonType implements StorageType<String>, CastTarget<String> {
    private final SqlDialect dialect;
    private final String name;

    protected NativeJsonType(SqlDialect dialect, String name) {
        this.dialect = dialect;
        this.name = name;
    }

    public final SqlDialect dialect() {
        return dialect;
    }

    public final String sqlName() {
        return name;
    }

    @Override
    public final Class<String> javaType() {
        return String.class;
    }

    @Override
    public final String ddlType(SqlDialect actual) {
        if (actual != dialect) throw new IllegalArgumentException(name + " requires " + dialect + "; found " + actual);
        return name;
    }

    @Override
    public String castTemplate(SqlDialect actual) {
        return "CAST({0} AS " + ddlType(actual) + ")";
    }

    @Override
    public final String prepareValue(String value) {
        return UnicodeStrings.requirePortable(value, "JSON document");
    }
}
