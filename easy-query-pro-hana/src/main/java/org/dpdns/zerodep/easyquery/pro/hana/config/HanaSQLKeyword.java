package org.dpdns.zerodep.easyquery.pro.hana.config;

import com.easy.query.core.configuration.dialect.SQLKeyword;

import java.util.Objects;

/** Quotes a single identifier; schema and table names are quoted separately by EasyQuery. */
public final class HanaSQLKeyword implements SQLKeyword {
    @Override
    public String getQuoteName(String identifier) {
        String quoted = quoteUnquotedName(identifier);
        if (identifier.startsWith("\"") && identifier.endsWith("\"") && identifier.length() >= 2) {
            String body = identifier.substring(1, identifier.length() - 1);
            if (body.isEmpty() || body.replace("\"\"", "").indexOf('"') >= 0) {
                throw new IllegalArgumentException("Invalid quoted HANA identifier: " + identifier);
            }
            return identifier;
        }
        return quoted;
    }

    /** 已由元数据解码的名称必须作为整体引用，不能再次解释为预引用的输入。 */
    public String quoteUnquotedName(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isEmpty() || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A HANA identifier must be nonempty and cannot contain NUL");
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    public String unquoteName(String identifier) {
        String quoted = getQuoteName(identifier);
        return quoted.substring(1, quoted.length() - 1).replace("\"\"", "\"");
    }
}
