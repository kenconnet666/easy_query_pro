package org.dpdns.zerodep.easyquery.pro.dsl.json;

import java.util.Objects;

/**
 * 已经编码的 JSON 输入，区分文档内容和 SQL NULL。
 * 本类不进行 Java 对象序列化，也不承担完整 JSON 语法解析；具体编码器由应用选择，数据库校验原生语法。
 */
public final class JsonInput {
    private static final JsonInput JSON_NULL = new JsonInput("null");
    private static final JsonInput SQL_NULL = new JsonInput(null);
    private final String json;

    private JsonInput(String json) {
        this.json = json;
    }

    /**
     * 接收非 null 的已编码 JSON 文本；Java 字符串本身不会再次被编码为 JSON 字符串值。
     */
    public static JsonInput encoded(String json) {
        return new JsonInput(Objects.requireNonNull(json, "json"));
    }

    /**
     * JSON 文档中的 null，仍然是一个非 NULL 的 SQL 文档值。
     */
    public static JsonInput jsonNull() {
        return JSON_NULL;
    }

    /**
     * SQL NULL，与 JSON 文本 "null" 的查询和修改语义不同。
     */
    public static JsonInput sqlNull() {
        return SQL_NULL;
    }

    public String json() {
        return json;
    }
}
