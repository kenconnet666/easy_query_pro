package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json;

import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;

import java.util.List;

/**
 * PostgreSQL JSONB 专属运算。包含、key 和原生 jsonpath 查询返回布尔 SQL 表达式；
 * 整文档修改保留 T 的业务读取器，文本提取则切换为字符串结果。
 * PgJsonPath 是 PG 原生语法，与共同的结构化 JsonPath 属于不同入口。
 */
public interface PgJsonbExpression<T> extends PgJsonExpression<T> {
    BooleanTypeExpression<Boolean> containsJson(JsonInput candidate);

    BooleanTypeExpression<Boolean> containedBy(JsonInput container);

    BooleanTypeExpression<Boolean> hasKey(String key);

    BooleanTypeExpression<Boolean> hasAnyKey(String... keys);

    BooleanTypeExpression<Boolean> hasAllKeys(String... keys);

    BooleanTypeExpression<Boolean> pathExists(PgJsonPath path);

    /**
     * silent 只控制原生 jsonpath 求值选项，不吞掉 JDBC、作用域或参数校验异常。
     */
    BooleanTypeExpression<Boolean> pathExists(PgJsonPath path, JsonInput variables, boolean silent);

    /**
     * path 是逐层路径分量，单个 "a.b" 仍是一个 key，不自动按点拆分。
     * createMissing 沿用 jsonb_set 的规则，不承诺自动创建任意缺失父对象。
     */
    PgJsonbExpression<T> setPath(List<String> path, JsonInput value, boolean createMissing);

    PgJsonbExpression<T> removeKey(String key);

    /**
     * 按路径分量删除目标，分量中的点、引号等仍作为 key 内容绑定。
     */
    PgJsonbExpression<T> removePath(List<String> path);
}
