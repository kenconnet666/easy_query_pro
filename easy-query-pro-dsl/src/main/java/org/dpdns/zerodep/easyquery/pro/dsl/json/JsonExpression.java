package org.dpdns.zerodep.easyquery.pro.dsl.json;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import com.easy.query.core.proxy.predicate.aggregate.DSLSQLFunctionAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.type.NativeJsonType;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ScalarComparisonRestriction;

/**
 * 保留完整 JSON 列业务映射的原生操作入口。
 *
 * <p>T 可以是业务对象，不要求实体字段改成 String。整文档修改沿用原 reader，提取文本、布尔等标量后
 * 则使用新的结果类型，不再套用整文档转换器。view 只声明已有存储语义，不生成 CAST 或改动实体元数据。
 * JSON 的比较与普通字符串比较不同，需要显式文本转换或相应方言的 JSON 运算。
 *
 * @param <T> 整列 JSON 的业务表示
 */
public interface JsonExpression<T> extends PropTypeColumn<T>, DSLSQLFunctionAvailable, ScalarComparisonRestriction {
    @Override
    default String scalarComparisonError() {
        return "JSON values require explicit text comparison or native JSON operators";
    }

    NativeJsonType sqlType();

    StringTypeExpression<String> asText();

    JsonFragmentExpression at(JsonPath path);

    StringTypeExpression<String> textAt(JsonPath path);

    BooleanTypeExpression<Boolean> booleanAt(JsonPath path);

    BooleanTypeExpression<Boolean> existsAt(JsonPath path);
}
