package org.dpdns.zerodep.easyquery.pro.dsl.type;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.UnicodeStrings;

import java.util.Objects;
import java.util.Locale;

/**
 * Database-specific JSON storage or a declared JSON text view; concrete descriptors select different expression APIs.
 */
public abstract class NativeJsonType implements StorageType<String>, CastTarget<String> {
    private final SqlDialect dialect;
    private final String name;

    protected NativeJsonType(SqlDialect dialect, String name) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.name = Objects.requireNonNull(name, "name");
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
        requireDialect(actual);
        return name;
    }

    public final void requireDialect(SqlDialect actual) {
        if (actual != dialect) throw new IllegalArgumentException(name + " requires " + dialect + "; found " + actual);
    }

    @Override
    public String castTemplate(SqlDialect actual) {
        return "CAST({0} AS " + ddlType(actual) + ")";
    }

    @Override
    public String prepareValue(String value) {
        return UnicodeStrings.requirePortable(value, "JSON document");
    }

    public boolean matchesDeclaration(String declaration) {
        return declaration.trim().toUpperCase(Locale.ROOT).split("[\\s(]", 2)[0].equals(name);
    }
}
