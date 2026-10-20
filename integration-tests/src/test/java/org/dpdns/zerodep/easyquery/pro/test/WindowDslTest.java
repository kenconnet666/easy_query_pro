package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.core.expression.segment.CloneableSQLSegment;
import com.easy.query.core.expression.segment.SQLEntityAliasSegment;
import com.easy.query.core.expression.sql.builder.AnonymousEntityTableExpressionBuilder;
import com.easy.query.core.enums.EasyBehaviorEnum;
import org.dpdns.zerodep.easyquery.pro.test.extension.ReportWindow;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProSQLSegmentFactory;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RowBoundary;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RowsFrame;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class WindowDslTest {
    static Stream<DefaultEasyEntityQuery> clients() {
        return Stream.of(TestClients.offline(new MySQLDatabaseConfiguration(), MySqlExtensions::configure),
                TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure),
                TestClients.offline(new HanaDatabaseConfiguration(), HanaExtensions::configure));
    }

    @ParameterizedTest
    @MethodSource("clients")
    void aggregatesReuseTheirFunctionsAndBindValuesOnce(DefaultEasyEntityQuery query) {
        var selected = query.queryable(TestProduct.class).select(p -> {
            var pro = ProDsl.of(p);
            var frame = pro.window(ReportWindow::new).partitionBy(p.name()).orderBy(p.id()).trailing(2);
            return Select.DRAFT.of(p.id(), pro.over(p.amount().sum(), frame),
                    pro.over(pro.value(new BigDecimal("2"), CommonTypes.decimal(10, 2)).sum(), pro.window()));
        });
        var sql = selected.cloneQueryable().toSQLResult();
        assertTrue(sql.getSQL().contains("ROWS BETWEEN 2 PRECEDING AND CURRENT ROW"), sql.getSQL());
        assertTrue(sql.getSQL().contains("OVER ()"), sql.getSQL());
        assertEquals(List.of(new BigDecimal("2.00")), sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(sql.getSQL(), selected.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("clients")
    void invalidAggregateAndValueWindowShapesFailEarly(DefaultEasyEntityQuery query) {
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).over(p.amount(), ProDsl.of(p).window())));
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).over(p.amount().sum(true), ProDsl.of(p).window())));
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).over(p.amount().sum(),
                ProDsl.of(p).window().rowsBetween(RowBoundary.preceding(1), RowBoundary.currentRow()))));
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).overValue(p.name(), ProDsl.of(p).window().orderBy(p.id())).nthValue(0)));
        assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> ProDsl.of(p).overValue(p.name(), ProDsl.of(p).window().orderBy(p.id())).prev(1)));
    }

    @ParameterizedTest
    @MethodSource("clients")
    void nestedWindowsAndAggregateWrappersFailBeforeSqlExecution(DefaultEasyEntityQuery query) {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> {
                    var pro = ProDsl.of(p);
                    return pro.over(pro.nativeCast(p.amount().sum(), CommonTypes.decimal(19, 4)), pro.window());
                }).toSQL()),
                () -> assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> {
                    var pro = ProDsl.of(p);
                    return pro.overValue(pro.over(p.amount().sum(), pro.window()), pro.window().orderBy(p.id())).firstValue();
                }).toSQL()),
                () -> assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> {
                    var pro = ProDsl.of(p);
                    return pro.over(pro.over(p.amount().sum(), pro.window()).sum(), pro.window());
                }).toSQL()),
                () -> assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> {
                    var pro = ProDsl.of(p);
                    return pro.over(p.amount().sum(), pro.window().orderBy(pro.over(p.id().max(), pro.window())));
                }).toSQL()),
                () -> assertThrows(IllegalArgumentException.class, () -> query.queryable(TestProduct.class).selectColumn(p -> {
                    var pro = ProDsl.of(p);
                    return pro.overValue(p.amount().sum().add(BigDecimal.ONE), pro.window().orderBy(p.id())).firstValue();
                }).toSQL()));
    }

    @ParameterizedTest
    @MethodSource("clients")
    void windowInspectionTraversesAliasesButStopsAtScalarSubqueries(DefaultEasyEntityQuery query) {
        var window = query.queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).over(p.amount().sum(), ProDsl.of(p).window())));
        var builder = ((AnonymousEntityTableExpressionBuilder) window.getClientQueryable().getSQLEntityExpressionBuilder().getTable(0)).getEntityQueryExpressionBuilder();
        var runtime = builder.getRuntimeContext();
        var segments = runtime.getSQLSegmentFactory();
        var expression = ((CloneableSQLSegment) builder.getProjects().getSQLSegments().get(0)).cloneSQLColumnSegment();
        ((SQLEntityAliasSegment) expression).setAlias(null);
        var alias = segments.createSQLColumnAsSegment(expression, "renamed", runtime);
        assertTrue(ExpressionScope.containsWindow(alias));
        assertTrue(ExpressionScope.containsWindow(alias.cloneSQLColumnSegment()));
        assertThrows(UnsupportedOperationException.class, () -> ProSQLSegmentFactory.validateDistinctCount(List.of(alias)));
        var scalar = segments.createSubQueryColumnSegment(null, window.getClientQueryable().limit(1), "scalar", runtime);
        assertFalse(ExpressionScope.containsWindow(scalar));
        assertFalse(ExpressionScope.containsWindow(scalar.cloneSQLColumnSegment()));
        assertDoesNotThrow(() -> ProSQLSegmentFactory.validateDistinctCount(List.of(scalar)));
        var inner = query.queryable(TestProduct.class).limit(1).selectColumn(p ->
                ProDsl.of(p).over(p.amount().sumBigDecimal(), ProDsl.of(p).window()));
        var outer = query.getEasyQueryClient().queryable(TestProduct.class).select(BigDecimal.class,
                s -> s.columnSubQueryAs(() -> inner, "amount"));
        assertDoesNotThrow(() -> outer.cloneQueryable().distinct().selectCount(Long.class).toSQL());
        var nativeOuter = query.getEasyQueryClient().queryable(TestProduct.class).select(BigDecimal.class,
                s -> s.sqlNativeSegment("{0}", c -> c.expression(window.getClientQueryable().limit(1))));
        assertDoesNotThrow(() -> nativeOuter.cloneQueryable().distinct().selectCount(Long.class).toSQL());
        var nativeSegment = (com.easy.query.core.expression.segment.SQLNativeSegment) new com.easy.query.core.func.SQLFunctionTranslateImpl(
                runtime.fx().anySQLFunction("1", ignored -> {
                })).toSQLSegment(builder.getExpressionContext(), builder.getTable(0).getEntityTable(), runtime, null);
        nativeSegment.setAlias("renamed");
        assertEquals("renamed", nativeSegment.cloneSQLColumnSegment().getAlias());
    }

    @ParameterizedTest
    @MethodSource("clients")
    void ordinarySmartPredicateOptimizationStillRuns(DefaultEasyEntityQuery query) {
        var selected = query.queryable(TestProduct.class).select(p -> Select.DRAFT.of(p.id())).where(p -> p.value1().eq(7));
        var builder = selected.getClientQueryable().getSQLEntityExpressionBuilder();
        builder.getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        selected.toSQL();
        var inner = ((AnonymousEntityTableExpressionBuilder) builder.getTable(0)).getEntityQueryExpressionBuilder();
        assertTrue(inner.hasWhere());
    }

    @ParameterizedTest
    @MethodSource("clients")
    void distinctWindowCountRequiresTheExplicitCteBoundary(DefaultEasyEntityQuery query) {
        var window = query.queryable(TestProduct.class).select(p -> Select.DRAFT.of(
                ProDsl.of(p).over(p.amount().sum(), ProDsl.of(p).window().partitionBy(p.name()))));
        var error = assertThrows(UnsupportedOperationException.class, () -> window.cloneQueryable().distinct().selectCount(Long.class).toSQL());
        assertTrue(error.getMessage().contains("toCteAs"));
        String bounded = window.cloneQueryable().distinct().toCteAs("window_values").selectCount(Long.class).toSQL();
        assertTrue(bounded.contains("COUNT(*)"), bounded);
        assertTrue(bounded.contains("DISTINCT"), bounded);
    }

    @Test
    void rowBoundsRejectInvalidDirectionsAndNormalizeZero() {
        assertEquals(RowBoundary.currentRow(), RowBoundary.preceding(0));
        assertEquals(RowBoundary.currentRow(), RowBoundary.following(0));
        assertThrows(IllegalArgumentException.class, () -> RowBoundary.preceding(-1));
        assertThrows(IllegalArgumentException.class, () -> new RowsFrame(RowBoundary.unboundedFollowing(), RowBoundary.unboundedFollowing()));
        assertThrows(IllegalArgumentException.class, () -> new RowsFrame(RowBoundary.currentRow(), RowBoundary.unboundedPreceding()));
        assertThrows(IllegalArgumentException.class, () -> new RowsFrame(RowBoundary.preceding(2), RowBoundary.preceding(3)));
    }
}
