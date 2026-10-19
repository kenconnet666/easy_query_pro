package org.dpdns.zerodep.easyquery.pro.hana.json;

import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

/** 提供显式部署 SQL，不创建 schema、不自动连接数据库，也不覆盖已有函数。 */
public final class HanaJsonSupport {
    private HanaJsonSupport() {
    }

    public static String arrayLengthFunctionName(String schema) {
        return new HanaSQLKeyword().getQuoteName(schema) + ".\"EQP_JSON_ARRAY_LENGTH\"";
    }

    public static String createArrayLengthFunctionSql(String schema) {
        // HANA 2.0 JSON_TABLE 无法关联外层查询别名，函数参数可安全提供逐行的 JSON 值。
        return "CREATE FUNCTION " + arrayLengthFunctionName(schema) + """
                 (INPUT_JSON NCLOB) RETURNS RESULT INTEGER
                LANGUAGE SQLSCRIPT SQL SECURITY INVOKER DETERMINISTIC AS
                BEGIN
                    IF :INPUT_JSON IS NULL THEN
                        RESULT := NULL;
                    ELSEIF TO_NVARCHAR(SUBSTRING(LTRIM(:INPUT_JSON, CHAR(32) || CHAR(9) || CHAR(10) || CHAR(13)),1,1)) <> '[' THEN
                        SIGNAL SQL_ERROR_CODE 10001 SET MESSAGE_TEXT = 'Expected a JSON array';
                    ELSE
                        SELECT COUNT(*) INTO RESULT
                        FROM JSON_TABLE(:INPUT_JSON, 'lax $[*]'
                            COLUMNS(EQP_ITEM_ORDINALITY FOR ORDINALITY) ERROR ON ERROR);
                    END IF;
                END
                """;
    }
}
