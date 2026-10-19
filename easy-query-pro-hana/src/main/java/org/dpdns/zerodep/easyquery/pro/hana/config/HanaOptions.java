package org.dpdns.zerodep.easyquery.pro.hana.config;

/** 不可变且按 client 隔离的 HANA 翻译选项。 */
public record HanaOptions(int maxJsonValueUtf16Units, int maxJsonFragmentBytes, String jsonSupportSchema) {
    public static final HanaOptions DEFAULT = new HanaOptions(5000);

    public HanaOptions(int maxJsonValueUtf16Units) {
        this(maxJsonValueUtf16Units, defaultFragmentCapacity(maxJsonValueUtf16Units), null);
    }

    public HanaOptions(int maxJsonValueUtf16Units, String jsonSupportSchema) {
        this(maxJsonValueUtf16Units, defaultFragmentCapacity(maxJsonValueUtf16Units), jsonSupportSchema);
    }

    public HanaOptions {
        if (maxJsonValueUtf16Units < 1 || maxJsonFragmentBytes < 1 || maxJsonFragmentBytes > Integer.MAX_VALUE - 2) {
            throw new IllegalArgumentException("JSON value UTF-16 units and fragment bytes must be positive and fit native length fields");
        }
        if (jsonSupportSchema != null) new HanaSQLKeyword().getQuoteName(jsonSupportSchema);
    }

    private static int defaultFragmentCapacity(int characters) {
        // 一个 UTF-16 单元转成 JSON 转义最多六字节，另为字符串引号保留两字节。
        try {
            return Math.addExact(Math.multiplyExact(characters, 6), 2);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Configure JSON fragment byte capacity explicitly for this value capacity", exception);
        }
    }
}
