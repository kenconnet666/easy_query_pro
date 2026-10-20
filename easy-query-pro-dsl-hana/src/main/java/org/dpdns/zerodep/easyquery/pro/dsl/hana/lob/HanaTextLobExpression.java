package org.dpdns.zerodep.easyquery.pro.dsl.hana.lob;

import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;

/**
 * HANA 文本 LOB 操作，完整 LOB 的 GROUP BY/ORDER BY/DISTINCT 限制仍按实际返回类型检查。
 * Java 类型为 String 不会自动解除这些 SQL 限制；需要标量结果时使用显式切片或转换。
 * 长度、切片和文本转换保留数据库的 SQL NULL 传播。
 */
public interface HanaTextLobExpression<T> extends HanaLobExpression<T> {
    @Override
    HanaTypes.TextLob sqlType();

    NumberTypeExpression<Long> utf16Length();

    /**
     * 原生一基 UTF-16 索引，长度限于 0..5000 单元，边界可能切断代理对。
     * 需要完整 Unicode 字符时，应由调用方选择相应边界，不能把参数当作 code point 下标。
     */
    StringTypeExpression<String> sliceUtf16(int start, int length);

    /**
     * 显式取得字符串结果，复用原生转换，不人为添加 5000 单元的 CAST 截断。
     * SQL 可用操作仍取决于转换后的实际返回类型。
     */
    StringTypeExpression<String> asText();
}
