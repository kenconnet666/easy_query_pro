package org.dpdns.zerodep.easyquery.pro.dsl.mysql.json;

import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;

import java.util.Objects;

/**
 * A single JSON_SET assignment; an ordered sequence may address the same path more than once.
 */
public record MySqlJsonChange(JsonPath path, JsonInput value) {
    public MySqlJsonChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
    }
}
