package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.expression.builder.AsSelector;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.parser.factory.SQLExpressionInvokeFactory;
import com.easy.query.core.expression.segment.builder.SQLBuilderSegment;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.expression.sql.builder.factory.ExpressionBuilderFactory;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.metadata.EntityMetadata;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProExpressionBuilderFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProProjectionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class DslExtensionTest {
    @Test
    void mountedFacadeAndExpressionKeepTheirConcreteTypes() {
        var client = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        var selected = client.queryable(TestProduct.class).select(p -> {
            ReportDsl report = ProDsl.of(p, ReportDsl::new);
            MoneyExpression cast = report.money(p.amount());
            MoneyExpression value = report.value(new BigDecimal("12.5"), CommonTypes.decimal(10, 2), MoneyExpression::new);
            MoneyExpression view = report.view(p.amount(), MoneyExpression::new);
            return Select.DRAFT.of(cast.taxed(), value.taxed(), view.taxed(), report.nullIfValue(p.name(), ""));
        });
        var rendered = selected.toSQLResult();
        assertTrue(rendered.getSQL().contains("DECIMAL(10,2)"), rendered.getSQL());
        assertEquals(List.of(new BigDecimal("1.20"), new BigDecimal("12.50"), new BigDecimal("1.20"), new BigDecimal("1.20"), ""),
                rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(rendered.getSQL(), selected.cloneQueryable().toSQL());
    }

    @Test
    void mountingRetainsDialectAndCrossClientChecks() {
        var mysql = TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        assertThrows(IllegalArgumentException.class, () -> mysql.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p, ReportDsl::new).money(p.amount())));
        var first = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        var other = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        var foreign = new AtomicReference<PropTypeColumn<BigDecimal>>();
        first.queryable(TestProduct.class).selectColumn(p -> {
            foreign.set(p.amount());
            return p.amount();
        });
        assertThrows(IllegalArgumentException.class, () -> other.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p, ReportDsl::new).money(foreign.get())));
        assertThrows(IllegalArgumentException.class, () -> other.queryable(TestProduct.class).selectColumn(p ->
                PgSqlDsl.of(p).value(new BigDecimal("1.234"), CommonTypes.decimal(10, 2), MoneyExpression::new)));
    }

    @Test
    void selectorHooksAndCteClonesKeepTheInstalledFactories() {
        var projections = new TrackingProjectionFactory();
        var builders = new TrackingBuilderFactory();
        var client = TestClients.offline(new PgSQLDatabaseConfiguration(), services -> {
            PgSqlExtensions.configure(services);
            services.addService(SQLExpressionInvokeFactory.class, projections);
            services.addService(ExpressionBuilderFactory.class, builders);
        });
        var selected = client.queryable(TestProduct.class).select(p -> Select.DRAFT.of(p.id(), p.amount())).toCteAs("extension_rows");
        var declaration = selected.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getDeclareExpressions().get(0);
        assertInstanceOf(TrackingBuilderFactory.TrackingCte.class, declaration);
        var cte = assertInstanceOf(EntityQueryExpressionBuilder.class, declaration);
        assertInstanceOf(TrackingBuilderFactory.TrackingCte.class, cte.cloneEntityExpressionBuilder());
        assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        assertTrue(projections.columns >= 2);
        assertTrue(builders.ctes >= 2);
    }

    private static class ReportDsl extends PgSqlDsl {
        protected ReportDsl(ExpressionScope scope) {
            super(scope);
        }

        MoneyExpression money(PropTypeColumn<?> value) {
            return nativeCast(value, CommonTypes.decimal(10, 2), MoneyExpression::new);
        }
    }

    private static class MoneyExpression extends NumberTypeExpressionImpl<BigDecimal> {
        protected MoneyExpression(ExpressionScope scope, PropTypeColumn<BigDecimal> source, Function<SQLFunc, SQLFunction> expression) {
            super(scope.context(), source.getTable(), source.getValue(), expression, BigDecimal.class);
        }

        NumberTypeExpression<BigDecimal> taxed() {
            return multiply(new BigDecimal("1.20"));
        }
    }

    private static class TrackingProjectionFactory extends ProProjectionFactory {
        int columns;

        @Override
        protected AsSelector createAsSelector(EntityQueryExpressionBuilder query, SQLBuilderSegment projects, EntityMetadata result) {
            return new ScalarAsSelector(query, projects, result) {
                @Override
                public AsSelector columnAs(TableAvailable table, String property, String alias) {
                    columns++;
                    return super.columnAs(table, property, alias);
                }
            };
        }
    }

    private static class TrackingBuilderFactory extends ProExpressionBuilderFactory {
        int ctes;

        @Override
        protected Cte createCte(String name, EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass, Map<String, ColumnReader> readers) {
            ctes++;
            return new TrackingCte(this, name, source, context, resultClass, readers);
        }

        private static class TrackingCte extends Cte {
            protected TrackingCte(ProExpressionBuilderFactory factory, String name, EntityQueryExpressionBuilder source,
                                  ExpressionContext context, Class<?> resultClass, Map<String, ColumnReader> readers) {
                super(factory, name, source, context, resultClass, readers);
            }
        }
    }
}
