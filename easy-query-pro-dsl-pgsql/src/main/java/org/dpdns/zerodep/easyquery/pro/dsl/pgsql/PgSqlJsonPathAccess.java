package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;

final class PgSqlJsonPathAccess implements JsonPathAccess {
    @Override
    public SQLFunction fragment(SQLFunc factory, SQLFunction document, JsonPath path) {
        // JSON 读取不先转成 JSONB，避免顺便改变重复键和数值表示的规则。
        SQLFunction node = factory.anySQLFunction("CAST({0} AS JSON)", args -> args.sqlFunc(document));
        for (JsonPath.Segment segment : path.segments()) {
            SQLFunction parent = node;
            if (segment instanceof JsonPath.Key) {
                JsonPath.Key key = (JsonPath.Key) segment;
                node = factory.anySQLFunction("({0} -> CAST({1} AS TEXT))", args -> args.sqlFunc(parent).value(key.name()));
            } else {
                int index = ((JsonPath.Index) segment).value();
                node = factory.anySQLFunction("({0} -> CAST({1} AS INTEGER))", args -> args.sqlFunc(parent).value(index));
            }
        }
        return node;
    }

    @Override
    public SQLFunction scalarText(SQLFunc factory, SQLFunction fragment) {
        return factory.anySQLFunction("(CASE WHEN json_typeof({0}) IN ('object','array') THEN NULL ELSE ({0} #>> ARRAY[]::TEXT[]) END)",
                args -> args.sqlFunc(fragment));
    }
}
