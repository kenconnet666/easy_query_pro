package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.func.def.enums.DateTimeDurationEnum;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.common.ToSQLResult;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class HanaFunctionCompositionIT {
    @Test
    void unicodeFunctionsRepeatNestedBoundValuesInSqlOrder() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        String source = "SELECT 1 AS \"id\",'a' AS \"name\" FROM DUMMY";
        EntityQueryable<TestProductProxy, TestProduct> rows = query.queryable(source, TestProduct.class);
        Query<Integer> index = rows.cloneQueryable().selectColumn(p -> p.name().concat("😀b").indexOf("b"));
        assertEquals(2, index.singleNotNull());
        assertEquals(2, index.cloneQueryable().singleNotNull());
        assertEquals("😀", rows.cloneQueryable().selectColumn(p -> p.name().concat("😀b").subString(1, 1)).singleNotNull());
        assertEquals("xxa😀b", rows.cloneQueryable().selectColumn(p -> p.name().concat("😀b").leftPad(5, 'x')).singleNotNull());
        ToSQLResult rendered = index.toSQLResult();
        assertEquals(rendered.getSQL().chars().filter(c -> c == '?').count(), rendered.getSqlContext().getParameters().size());
        assertEquals(Arrays.asList("😀b", "b", "😀b", "😀b", "b"), rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
    }

    @Test
    void formattingRepeatsTheBoundDateArithmeticWithoutInterpolatingLiterals() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        LocalDateTime date = LocalDateTime.of(2024, 2, 29, 12, 34, 56);
        String pattern = "yyyy-MM-dd HH:mm:ss '{0}中文'";
        Query<String> selected = query.queryable("SELECT CAST(? AS TIMESTAMP) AS \"occurred\" FROM DUMMY", TestEvent.class, Arrays.asList(date))
                .selectColumn(e -> e.occurred().plusDays(1).format(pattern));
        assertEquals(date.plusDays(1).format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT)), selected.singleNotNull());
        assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        assertFalse(selected.toSQL().contains("中文"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void reversedDurationArgumentsKeepTheirBindingOrder() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        LocalDateTime first = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime second = first.plusDays(2);
        Query<Long> selected = query.queryable("SELECT 1 AS \"id\" FROM DUMMY", TestProduct.class).selectColumn(p ->
                new NumberTypeExpressionImpl<Long>(p.getEntitySQLContext(), p.getTable(), null,
                        fx -> fx.duration(args -> args.value(first).value(second), DateTimeDurationEnum.Days), Long.class));
        assertEquals(-2L, selected.singleNotNull());
        assertEquals(Arrays.asList(second, first), selected.toSQLResult().getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
    }

    @Test
    void stringAggregationOrdersNestedParametersBeforeTheSeparator() throws SQLException {
        DefaultEasyEntityQuery query = HanaTestDatabase.client();
        Query<String> selected = query.queryable("SELECT 1 AS \"id\",'A' AS \"name\" FROM DUMMY UNION ALL SELECT 2,'B' FROM DUMMY", TestProduct.class)
                .selectColumn(p -> new StringTypeExpressionImpl<String>(p.getEntitySQLContext(), p.getTable(), null,
                        fx -> fx.joining(args -> {
                            args.value("|");
                            PropTypeColumn.columnFuncSelector(args, p.name().concat("!"));
                            args.sqlFunc(fx.anySQLFunction("{0} ASC", order -> PropTypeColumn.columnFuncSelector(order, p.id())));
                        }, false), String.class));
        assertEquals("A!|B!", selected.singleNotNull());
        assertEquals(Arrays.asList("!", "|"), selected.toSQLResult().getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
    }
}
