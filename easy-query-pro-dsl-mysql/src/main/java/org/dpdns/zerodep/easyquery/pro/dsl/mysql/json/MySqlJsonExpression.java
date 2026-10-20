package org.dpdns.zerodep.easyquery.pro.dsl.mysql.json;

import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;

/**
 * MySQL 原生 JSON 操作；路径父节点、数组包装和 NULL 遵循数据库规则。
 * 整文档 set/remove 保留 T 的 reader，包含/路径判断返回可继续组合的布尔表达式。
 */
public interface MySqlJsonExpression<T> extends JsonExpression<T> {
    BooleanTypeExpression<Boolean> containsJson(JsonInput candidate);

    BooleanTypeExpression<Boolean> containsJson(JsonInput candidate, JsonPath path);

    BooleanTypeExpression<Boolean> containsAnyPath(JsonPath... paths);

    BooleanTypeExpression<Boolean> containsAllPaths(JsonPath... paths);

    MySqlJsonExpression<T> setPath(JsonPath path, JsonInput value);

    /**
     * 按传入顺序从左到右应用修改，至少提供一项；后续路径看到的是前面修改后的文档。
     */
    MySqlJsonExpression<T> setPaths(MySqlJsonChange... changes);

    MySqlJsonExpression<T> removePath(JsonPath path);

    /**
     * 按传入顺序删除路径，拒绝空列表和根路径删除；缺失路径的行为交给原生 JSON_REMOVE。
     */
    MySqlJsonExpression<T> removePaths(JsonPath... paths);
}
