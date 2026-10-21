package org.dpdns.zerodep.easyquery.pro.dsl.mysql.json;

import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;

import java.util.Objects;

/**
 * A single JSON_SET assignment; an ordered sequence may address the same path more than once.
 */
public final class MySqlJsonChange {
    private final JsonPath path;
    private final JsonInput value;

    public MySqlJsonChange(JsonPath path, JsonInput value) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        this.path = path;
        this.value = value;
    }

    public JsonPath path() {
        return path;
    }

    public JsonInput value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MySqlJsonChange)) return false;
        MySqlJsonChange other = (MySqlJsonChange) o;
        return Objects.equals(path, other.path)
                && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, value);
    }

    @Override
    public String toString() {
        return "MySqlJsonChange[path=" + path + ", value=" + value + "]";
    }
}
