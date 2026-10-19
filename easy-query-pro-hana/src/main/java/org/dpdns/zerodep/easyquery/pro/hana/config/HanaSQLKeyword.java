package org.dpdns.zerodep.easyquery.pro.hana.config;

import com.easy.query.core.configuration.dialect.SQLKeyword;

import java.util.Objects;

/** Quotes a single identifier; schema and table names are quoted separately by EasyQuery. */
public final class HanaSQLKeyword implements SQLKeyword {
    @Override
    public String getQuoteName(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isEmpty() || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A HANA identifier must be nonempty and cannot contain NUL");
        }
        if (identifier.startsWith("\"") && identifier.endsWith("\"") && identifier.length() >= 2) {
            String body = identifier.substring(1, identifier.length() - 1);
            if (body.isEmpty() || body.replace("\"\"", "").indexOf('"') >= 0) {
                throw new IllegalArgumentException("Invalid quoted HANA identifier: " + identifier);
            }
            return identifier;
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
