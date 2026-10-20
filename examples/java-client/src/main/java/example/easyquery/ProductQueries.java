package example.easyquery;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import example.easyquery.proxy.ProductProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;

import java.math.BigDecimal;

public final class ProductQueries {
    private ProductQueries() {
    }

    public static EntityQueryable<ProductProxy, Product> named(EasyEntityQuery client, String table, String name) {
        return client.queryable(ProductProxy.createTable()).asTable(table)
                .where(product -> ProDsl.of(product).nullSafeEqualsValue(product.name(), name).eq(true))
                .orderBy(product -> product.id().asc());
    }

    public static EntityQueryable<ProductProxy, Product> atLeast(EasyEntityQuery client, String table, BigDecimal amount) {
        return client.queryable(ProductProxy.createTable()).asTable(table)
                .where(product -> ProDsl.of(product).nativeCast(product.amount(), CommonTypes.decimal(19, 2)).ge(amount))
                .orderBy(product -> product.id().asc());
    }
}
