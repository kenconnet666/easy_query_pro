package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.json.HanaJsonSupport;

final class HanaJsonPathAccess implements JsonPathAccess {
    private final String documentFunction;

    HanaJsonPathAccess(HanaOptions options) {
        documentFunction = options.jsonSupportSchema() == null ? null : HanaJsonSupport.documentFunctionName(options.jsonSupportSchema());
    }

    @Override
    public void requireNavigationSupport() {
        if (documentFunction == null) {
            throw new IllegalStateException("HANA JSON paths require HanaOptions.jsonSupportSchema and explicit deployment of HanaJsonSupport.createDocumentFunctionSql");
        }
    }

    @Override
    public SQLFunction fragment(SQLFunc factory, SQLFunction document, JsonPath path) {
        SQLFunction node = filter(factory, document, "ANY");
        if (path.segments().isEmpty()) {
            SQLFunction wrapped = wrap(factory, node);
            return factory.jsonArrayExtractByIndex(args -> args.sqlFunc(wrapped).format(0));
        }
        for (JsonPath.Segment segment : path.segments()) {
            if (segment instanceof JsonPath.Key) {
                JsonPath.Key key = (JsonPath.Key) segment;
                SQLFunction parent = filter(factory, node, "OBJECT");
                node = factory.jsonObjectExtract(args -> args.sqlFunc(parent).format(key.name()));
            } else {
                SQLFunction parent = filter(factory, node, "ARRAY");
                int index = ((JsonPath.Index) segment).value();
                node = factory.jsonArrayExtractByIndex(args -> args.sqlFunc(parent).format(index));
            }
        }
        return node;
    }

    @Override
    public SQLFunction scalarText(SQLFunc factory, SQLFunction fragment) {
        SQLFunction wrapped = wrap(factory, filter(factory, fragment, "SCALAR"));
        return factory.jsonArrayByIndex(args -> args.sqlFunc(wrapped).format(0));
    }

    private SQLFunction filter(SQLFunc factory, SQLFunction document, String kind) {
        requireNavigationSupport();
        // 限定名作为 format 实参传递，schema 名中的花括号不能变成模板占位符。
        return factory.anySQLFunction("{1}({0},{2})", args -> args.sqlFunc(document).format(documentFunction).value(kind));
    }

    private static SQLFunction wrap(SQLFunc factory, SQLFunction document) {
        return factory.anySQLFunction("(TO_NCLOB('[') || {0} || ']')", args -> args.sqlFunc(document));
    }
}
