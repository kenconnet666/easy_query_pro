package org.dpdns.zerodep.easyquery.pro.hana.json;

import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

/**
 * 提供显式部署 SQL，不创建 schema、不自动连接数据库，也不覆盖已有函数。
 */
public final class HanaJsonSupport {
    private HanaJsonSupport() {
    }

    public static String arrayLengthFunctionName(String schema) {
        return new HanaSQLKeyword().getQuoteName(schema) + ".\"EQP_JSON_ARRAY_LENGTH\"";
    }

    public static String documentFunctionName(String schema) {
        return new HanaSQLKeyword().getQuoteName(schema) + ".\"EQP_JSON_DOCUMENT\"";
    }

    /**
     * Validates one JSON value and filters its kind without serializing the document on the JVM.
     */
    public static String createDocumentFunctionSql(String schema) {
        // SQLScript 的 NCLOB 参数保留 JSON_TABLE 的单层边界；不能用内联参数拼接代替。
        return "CREATE FUNCTION " + documentFunctionName(schema) + """
                 (INPUT_JSON NCLOB, EXPECTED_KIND NVARCHAR(8)) RETURNS RESULT NCLOB
                LANGUAGE SQLSCRIPT SQL SECURITY INVOKER DETERMINISTIC AS
                BEGIN
                    DECLARE ITEM_COUNT INTEGER;
                    DECLARE FIRST_CHAR NVARCHAR(1);
                    IF :EXPECTED_KIND IS NULL OR :EXPECTED_KIND NOT IN ('ANY','OBJECT','ARRAY','SCALAR') THEN
                        SIGNAL SQL_ERROR_CODE 10003 SET MESSAGE_TEXT = 'Invalid JSON access kind';
                    END IF;
                    IF :INPUT_JSON IS NULL THEN
                        RESULT := NULL;
                    ELSE
                        SELECT COUNT(*) INTO ITEM_COUNT
                        FROM JSON_TABLE(('[' || :INPUT_JSON || ']'), 'strict $[*]'
                            COLUMNS(EQP_ITEM_ORDINALITY FOR ORDINALITY) ERROR ON ERROR);
                        IF :ITEM_COUNT <> 1 THEN
                            SIGNAL SQL_ERROR_CODE 10002 SET MESSAGE_TEXT = 'Expected exactly one JSON value';
                        END IF;
                        FIRST_CHAR := TO_NVARCHAR(SUBSTRING(LTRIM(:INPUT_JSON, CHAR(32) || CHAR(9) || CHAR(10) || CHAR(13)),1,1));
                        IF :EXPECTED_KIND = 'OBJECT' AND :FIRST_CHAR <> '{' THEN RESULT := NULL;
                        ELSEIF :EXPECTED_KIND = 'ARRAY' AND :FIRST_CHAR <> '[' THEN RESULT := NULL;
                        ELSEIF :EXPECTED_KIND = 'SCALAR' AND :FIRST_CHAR IN ('{','[') THEN RESULT := NULL;
                        ELSE RESULT := :INPUT_JSON;
                        END IF;
                    END IF;
                END
                """;
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
