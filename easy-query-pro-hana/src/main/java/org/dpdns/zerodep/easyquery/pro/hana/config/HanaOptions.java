package org.dpdns.zerodep.easyquery.pro.hana.config;

import java.util.Objects;

/** 不可变且按 client 隔离的 HANA 翻译选项。 */
public final class HanaOptions {
    private final int maxJsonValueUtf16Units;
    private final int maxJsonFragmentBytes;
    private final String jsonSupportSchema;

    public static final HanaOptions DEFAULT = new HanaOptions(5000);

    public HanaOptions(int maxJsonValueUtf16Units) {
        this(maxJsonValueUtf16Units, defaultFragmentCapacity(maxJsonValueUtf16Units), null);
    }

    public HanaOptions(int maxJsonValueUtf16Units, String jsonSupportSchema) {
        this(maxJsonValueUtf16Units, defaultFragmentCapacity(maxJsonValueUtf16Units), jsonSupportSchema);
    }

    public HanaOptions(int maxJsonValueUtf16Units, int maxJsonFragmentBytes, String jsonSupportSchema) {
        if (maxJsonValueUtf16Units < 1 || maxJsonFragmentBytes < 1 || maxJsonFragmentBytes > Integer.MAX_VALUE - 2) {
            throw new IllegalArgumentException("JSON value UTF-16 units and fragment bytes must be positive and fit native length fields");
        }
        if (jsonSupportSchema != null) new HanaSQLKeyword().getQuoteName(jsonSupportSchema);
        this.maxJsonValueUtf16Units = maxJsonValueUtf16Units;
        this.maxJsonFragmentBytes = maxJsonFragmentBytes;
        this.jsonSupportSchema = jsonSupportSchema;
    }

    private static int defaultFragmentCapacity(int characters) {
        // 一个 UTF-16 单元转成 JSON 转义最多六字节，另为字符串引号保留两字节。
        try {
            return Math.addExact(Math.multiplyExact(characters, 6), 2);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Configure JSON fragment byte capacity explicitly for this value capacity", exception);
        }
    }

    public int maxJsonValueUtf16Units() {
        return maxJsonValueUtf16Units;
    }

    public int maxJsonFragmentBytes() {
        return maxJsonFragmentBytes;
    }

    public String jsonSupportSchema() {
        return jsonSupportSchema;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HanaOptions)) return false;
        HanaOptions other = (HanaOptions) o;
        return maxJsonValueUtf16Units == other.maxJsonValueUtf16Units
                && maxJsonFragmentBytes == other.maxJsonFragmentBytes
                && Objects.equals(jsonSupportSchema, other.jsonSupportSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxJsonValueUtf16Units, maxJsonFragmentBytes, jsonSupportSchema);
    }

    @Override
    public String toString() {
        return "HanaOptions[maxJsonValueUtf16Units=" + maxJsonValueUtf16Units + ", maxJsonFragmentBytes=" + maxJsonFragmentBytes + ", jsonSupportSchema=" + jsonSupportSchema + "]";
    }
}
