package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;

import java.util.ArrayList;

final class MySqlJsonPathAccess implements JsonPathAccess {
    @Override
    public SQLFunction fragment(SQLFunc factory, SQLFunction document, JsonPath path) {
        // Connector/J 在 NO_BACKSLASH_ESCAPES 的客户端预处理下会用 binary 十六进制绑定字符串。
        SQLFunction unicodeDocument = factory.anySQLFunction("CONVERT({0} USING utf8mb4)", args -> args.sqlFunc(document));
        ArrayList<String> prefixes = new ArrayList<String>();
        StringBuilder sql = new StringBuilder();
        JsonPath prefix = JsonPath.root();
        for (JsonPath.Segment segment : path.segments()) {
            sql.append(prefixes.isEmpty() ? "CASE WHEN " : " AND ");
            prefixes.add(prefix.toSqlJsonPath());
            sql.append("JSON_TYPE(JSON_EXTRACT({0},CONVERT({").append(prefixes.size()).append("} USING utf8mb4))) = '");
            if (segment instanceof JsonPath.Key) {
                JsonPath.Key key = (JsonPath.Key) segment;
                sql.append("OBJECT'");
                prefix = prefix.key(key.name());
            } else {
                sql.append("ARRAY'");
                prefix = prefix.index(((JsonPath.Index) segment).value());
            }
        }
        // 用前缀类型约束关闭 MySQL 的标量自动包装；只生成线性数量的根表达式引用。
        if (!prefixes.isEmpty()) sql.append(" THEN ");
        sql.append("JSON_EXTRACT({0},CONVERT({").append(prefixes.size() + 1).append("} USING utf8mb4))");
        if (!prefixes.isEmpty()) sql.append(" ELSE NULL END");
        return factory.anySQLFunction("(" + sql + ")", args -> {
            args.sqlFunc(unicodeDocument);
            prefixes.forEach(args::value);
            args.value(path.toSqlJsonPath());
        });
    }

    @Override
    public SQLFunction scalarText(SQLFunc factory, SQLFunction fragment) {
        return factory.anySQLFunction("(CASE WHEN JSON_TYPE({0}) IN ('OBJECT','ARRAY','NULL') THEN NULL ELSE JSON_UNQUOTE({0}) END)",
                args -> args.sqlFunc(fragment));
    }

    @Override
    public SQLFunction serializedText(SQLFunc factory, SQLFunction fragment) {
        return factory.anySQLFunction("CAST({0} AS CHAR CHARACTER SET utf8mb4)", args -> args.sqlFunc(fragment));
    }
}
