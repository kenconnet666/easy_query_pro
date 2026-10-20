package org.dpdns.zerodep.easyquery.pro.dsl.json;

import java.util.Objects;

/**
 * Already encoded JSON; no Java object serialization is performed.
 */
public final class JsonInput {
    private static final JsonInput JSON_NULL = new JsonInput("null");
    private static final JsonInput SQL_NULL = new JsonInput(null);
    private final String json;

    private JsonInput(String json) {
        this.json = json;
    }

    public static JsonInput encoded(String json) {
        return new JsonInput(Objects.requireNonNull(json, "json"));
    }

    public static JsonInput jsonNull() {
        return JSON_NULL;
    }

    public static JsonInput sqlNull() {
        return SQL_NULL;
    }

    public String json() {
        return json;
    }
}
