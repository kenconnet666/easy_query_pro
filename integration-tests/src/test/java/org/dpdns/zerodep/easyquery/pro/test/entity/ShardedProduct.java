package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.ShardingTableKey;
import com.easy.query.core.annotation.ShardingDataSourceKey;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.enums.sharding.ShardingOperatorEnum;
import com.easy.query.core.expression.lambda.RouteFunction;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.metadata.ActualTable;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import com.easy.query.core.sharding.initializer.EntityShardingInitializer;
import com.easy.query.core.sharding.initializer.ShardingEntityBuilder;
import com.easy.query.core.sharding.route.table.abstraction.AbstractTableRoute;
import com.easy.query.core.sharding.route.datasource.abstraction.AbstractDataSourceRoute;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ShardedProductProxy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.LinkedHashMap;

@Table(value = "eqp_sharded_product", shardingInitializer = ShardedProduct.Tables.class)
@EntityProxy
public class ShardedProduct implements ProxyEntityAvailable<ShardedProduct, ShardedProductProxy> {
    @Column(primaryKey = true)
    @ShardingTableKey
    @ShardingDataSourceKey
    private Integer id;
    private String name;
    private BigDecimal amount;
    private LocalDateTime created;

    public ShardedProduct() {
    }

    public ShardedProduct(Integer id, String name, BigDecimal amount) {
        this.id = id;
        this.name = name;
        this.amount = amount;
        this.created = LocalDateTime.of(2024, 1, 30, 0, 0).plusDays(id);
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDateTime getCreated() {
        return created;
    }

    public void setCreated(LocalDateTime created) {
        this.created = created;
    }

    public static final class Tables implements EntityShardingInitializer<ShardedProduct> {
        private final Map<String, Collection<String>> tables;

        public Tables(Map<String, Collection<String>> tables) {
            var snapshot = new LinkedHashMap<String, Collection<String>>();
            tables.forEach((source, names) -> snapshot.put(source, List.copyOf(names)));
            this.tables = Map.copyOf(snapshot);
        }

        @Override
        public void configure(ShardingEntityBuilder<ShardedProduct> builder) {
            builder.actualTableNameInit(tables);
        }
    }

    public static final class SourceRoute extends AbstractDataSourceRoute<ShardedProduct> {
        private final List<String> sources;

        public SourceRoute(List<String> sources) {
            this.sources = List.copyOf(sources);
        }

        @Override
        protected RouteFunction<String> getRouteFilter(TableAvailable table, Object value, ShardingOperatorEnum operator, boolean withEntity) {
            if (operator != ShardingOperatorEnum.EQUAL) return source -> true;
            String target = sources.get(Math.floorMod(((Number) value).intValue(), sources.size()));
            return source -> source.equals(target);
        }
    }

    public static final class Route extends AbstractTableRoute<ShardedProduct> {
        private final List<String> tables;

        public Route(List<String> tables) {
            this.tables = List.copyOf(tables);
        }

        @Override
        protected RouteFunction<ActualTable> getRouteFilter(TableAvailable table, Object value, ShardingOperatorEnum operator, boolean withEntity) {
            if (operator != ShardingOperatorEnum.EQUAL) return actual -> true;
            String target = tables.get(Math.floorMod(((Number) value).intValue(), tables.size()));
            return actual -> actual.getActualTableName().equals(target);
        }
    }
}
