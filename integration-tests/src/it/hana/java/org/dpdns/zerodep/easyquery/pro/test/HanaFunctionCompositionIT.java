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
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class HanaFunctionCompositionIT {
    @Test
    void unicodeFunctionsRepeatNestedBoundValuesInSqlOrder() throws SQLException {
        var query = HanaTestDatabase.client();
        String source = "SELECT 1 AS \"id\",'a' AS \"name\" FROM DUMMY";
        var rows = query.queryable(source, TestProduct.class);
        var index = rows.cloneQueryable().selectColumn(p -> p.name().concat("😀b").indexOf("b"));
        assertEquals(2, index.singleNotNull());
        assertEquals(2, index.cloneQueryable().singleNotNull());
        assertEquals("😀", rows.cloneQueryable().selectColumn(p -> p.name().concat("😀b").subString(1, 1)).singleNotNull());
        assertEquals("xxa😀b", rows.cloneQueryable().selectColumn(p -> p.name().concat("😀b").leftPad(5, 'x')).singleNotNull());
        var rendered = index.toSQLResult();
        assertEquals(rendered.getSQL().chars().filter(c -> c == '?').count(), rendered.getSqlContext().getParameters().size());
        assertEquals(List.of("😀b", "b", "😀b", "😀b", "b"), rendered.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
    }

    @Test
    void formattingRepeatsTheBoundDateArithmeticWithoutInterpolatingLiterals() throws SQLException {
        var query = HanaTestDatabase.client();
        var date = LocalDateTime.of(2024, 2, 29, 12, 34, 56);
        String pattern = "yyyy-MM-dd HH:mm:ss '{0}中文'";
        var selected = query.queryable("SELECT CAST(? AS TIMESTAMP) AS \"occurred\" FROM DUMMY", TestEvent.class, List.of(date))
                .selectColumn(e -> e.occurred().plusDays(1).format(pattern));
        assertEquals(date.plusDays(1).format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT)), selected.singleNotNull());
        assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        assertFalse(selected.toSQL().contains("中文"));
    }

    @Test
    @SuppressWarnings("deprecation")
    void reversedDurationArgumentsKeepTheirBindingOrder() throws SQLException {
        var query = HanaTestDatabase.client();
        var first = LocalDateTime.of(2024, 1, 1, 0, 0);
        var second = first.plusDays(2);
        var selected = query.queryable("SELECT 1 AS \"id\" FROM DUMMY", TestProduct.class).selectColumn(p ->
                new NumberTypeExpressionImpl<Long>(p.getEntitySQLContext(), p.getTable(), null,
                        fx -> fx.duration(args -> args.value(first).value(second), DateTimeDurationEnum.Days), Long.class));
        assertEquals(-2L, selected.singleNotNull());
        assertEquals(List.of(second, first), selected.toSQLResult().getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
    }

    @Test
    void stringAggregationOrdersNestedParametersBeforeTheSeparator() throws SQLException {
        var query = HanaTestDatabase.client();
        var selected = query.queryable("SELECT 1 AS \"id\",'A' AS \"name\" FROM DUMMY UNION ALL SELECT 2,'B' FROM DUMMY", TestProduct.class)
                .selectColumn(p -> new StringTypeExpressionImpl<String>(p.getEntitySQLContext(), p.getTable(), null,
                        fx -> fx.joining(args -> {
                            args.value("|");
                            PropTypeColumn.columnFuncSelector(args, p.name().concat("!"));
                            args.sqlFunc(fx.anySQLFunction("{0} ASC", order -> PropTypeColumn.columnFuncSelector(order, p.id())));
                        }, false), String.class));
        assertEquals("A!|B!", selected.singleNotNull());
        assertEquals(List.of("!", "|"), selected.toSQLResult().getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
    }
}
