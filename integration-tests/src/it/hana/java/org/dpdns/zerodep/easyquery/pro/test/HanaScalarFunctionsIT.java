package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.func.def.enums.MathMethodEnum;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HanaScalarFunctionsIT {
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createClient() throws SQLException {
        query = HanaTestDatabase.client();
    }

    @Test
    void stringFunctionsRetainBindingUnicodeAndIndexSemantics() {
        String value = "O'Reilly %_!\\中文";
        var source = source(value, BigDecimal.ONE);
        assertEquals(value.length(), source.cloneQueryable().selectColumn(p -> p.name().length()).toList().get(0));
        assertEquals(value.indexOf("_!"), source.cloneQueryable().selectColumn(p -> p.name().indexOf("_!")).toList().get(0));
        assertEquals(-1, source.cloneQueryable().selectColumn(p -> p.name().indexOf("missing")).toList().get(0));
        assertEquals("O'Reilly", source.cloneQueryable().selectColumn(p -> p.name().subString(0, 8)).toList().get(0));
        assertEquals(value + "-tail", source.cloneQueryable()
                .selectColumn(p -> p.name().concat(parts -> {
                    parts.value("-");
                    parts.value("tail");
                }))
                .toList().get(0));
        assertNull(source(null, BigDecimal.ONE).selectColumn(p -> p.name().concat("suffix")).toList().get(0));
    }

    @Test
    void likeTreatsWildcardCharactersAsValuesAndCanBeRenderedRepeatedly() {
        var selection = source("prefix%_!\\suffix", BigDecimal.ONE)
                .where(p -> p.name().toUpper().like("%_!\\"));
        var first = selection.toSQLResult();
        var second = selection.toSQLResult();
        assertEquals(first.getSQL(), second.getSQL());
        assertEquals(first.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList(),
                second.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertFalse(first.getSQL().contains("prefix"));
        assertEquals(1, selection.toList().size());
        assertTrue(source("ordinary", BigDecimal.ONE).where(p -> p.name().toUpper().like("%_!\\")).toList().isEmpty());
        assertEquals(1, source("prefix%_!\\suffix", BigDecimal.ONE)
                .where(p -> p.name().startsWith("prefix%_")).where(p -> p.name().endsWith("!\\suffix")).toList().size());
    }

    @Test
    void mathUsesHanaFunctionsWithoutChangingNumericMeaning() {
        assertDecimal("8", source("math", new BigDecimal("2")).selectColumn(p -> p.amount().pow(new BigDecimal("3"))).toList().get(0));
        assertDecimal("2", source("math", new BigDecimal("100")).selectColumn(p -> p.amount().log10()).toList().get(0));
        assertDecimal("0", source("math", BigDecimal.ONE).selectColumn(p -> p.amount().log()).toList().get(0));
        assertDecimal("-1", source("math", new BigDecimal("-1.8")).selectColumn(p -> p.amount().truncate()).toList().get(0));
        assertDecimal("2", source("math", new BigDecimal("1.2")).selectColumn(p -> p.amount().ceiling()).toList().get(0));
    }

    @Test
    void supplementaryCharactersUseCodePointLengthsAndOffsets() {
        String value = "a😀中\nz";
        assertEquals(5, source(value, BigDecimal.ONE).selectColumn(p -> p.name().length()).toList().get(0));
        assertEquals("😀", source(value, BigDecimal.ONE).selectColumn(p -> p.name().subString(1, 1)).toList().get(0));
        assertEquals("中\n", source(value, BigDecimal.ONE).selectColumn(p -> p.name().subString(2, 2)).toList().get(0));
        assertEquals("中\nz", source(value, BigDecimal.ONE).selectColumn(p -> p.name().subString(2, Integer.MAX_VALUE)).toList().get(0));
        assertEquals(2, source(value, BigDecimal.ONE).selectColumn(p -> p.name().indexOf("中")).toList().get(0));
        assertEquals("", source(value, BigDecimal.ONE).selectColumn(p -> p.name().subString(99, 1)).toList().get(0));
        assertEquals("", source(value, BigDecimal.ONE).selectColumn(p -> p.name().subString(0, 0)).toList().get(0));
        assertNull(source(null, BigDecimal.ONE).selectColumn(p -> p.name().subString(0, 1)).toList().get(0));
    }

    @Test
    void paddingSharesCodePointBoundariesWithSubstring() {
        assertEquals("xx😀中", source("😀中", BigDecimal.ONE).selectColumn(p -> p.name().leftPad(4, 'x')).toList().get(0));
        assertEquals("😀中xx", source("😀中", BigDecimal.ONE).selectColumn(p -> p.name().rightPad(4, 'x')).toList().get(0));
        assertEquals("😀", source("😀中", BigDecimal.ONE).selectColumn(p -> p.name().leftPad(1, 'x')).toList().get(0));
        assertEquals("😀", source("😀中", BigDecimal.ONE).selectColumn(p -> p.name().rightPad(1)).toList().get(0));
        assertEquals("  😀中", source("😀中", BigDecimal.ONE).selectColumn(p -> p.name().leftPad(4)).toList().get(0));
        assertEquals("", source("😀中", BigDecimal.ONE).selectColumn(p -> p.name().rightPad(0)).toList().get(0));
        assertNull(source(null, BigDecimal.ONE).selectColumn(p -> p.name().leftPad(3)).toList().get(0));
        assertEquals("😀x😀中", source("中", BigDecimal.ONE).selectColumn(p -> new StringTypeExpressionImpl<String>(
                p.getEntitySQLContext(), p.getTable(), null, fx -> fx.leftPad(args -> {
            PropTypeColumn.columnFuncSelector(args, p.name());
            args.value(4).value("😀x");
        }), String.class)).toList().get(0));
    }

    @Test
    void emptyAndBlankPredicatesKeepSpacesDistinctFromEmptyStrings() {
        for (String value : Arrays.asList(null, "", " ", "  ", "\t", "text")) {
            boolean empty = value == null || value.isEmpty();
            boolean blank = value == null || value.chars().allMatch(c -> c == ' ');
            assertEquals(empty, !source(value, BigDecimal.ONE).where(p -> p.name().toUpper().isEmpty(true)).toList().isEmpty());
            assertEquals(!empty, !source(value, BigDecimal.ONE).where(p -> p.name().toUpper().isNotEmpty(true)).toList().isEmpty());
            assertEquals(blank, !source(value, BigDecimal.ONE).where(p -> p.name().toUpper().isBlank(true)).toList().isEmpty());
            assertEquals(!blank, !source(value, BigDecimal.ONE).where(p -> p.name().toUpper().isNotBlank(true)).toList().isEmpty());
        }
    }

    @Test
    void unchangedStringFunctionsExecuteThroughTheExistingFactories() {
        assertEquals("a中", source("A中", BigDecimal.ONE).selectColumn(p -> p.name().toLower()).toList().get(0));
        assertEquals("A中", source("a中", BigDecimal.ONE).selectColumn(p -> p.name().toUpper()).toList().get(0));
        assertEquals("中", source(" 中 ", BigDecimal.ONE).selectColumn(p -> p.name().trim()).toList().get(0));
        assertEquals("中 ", source(" 中 ", BigDecimal.ONE).selectColumn(p -> p.name().ltrim()).toList().get(0));
        assertEquals(" 中", source(" 中 ", BigDecimal.ONE).selectColumn(p -> p.name().rtrim()).toList().get(0));
        assertEquals("a😀b", source("a中b", BigDecimal.ONE).selectColumn(p -> p.name().replace("中", "😀")).toList().get(0));
        assertTrue(source("b", BigDecimal.ONE).where(p -> p.name().compareTo("a").gt(0)).any());
        assertFalse(source("a", BigDecimal.ONE).where(p -> p.name().compareTo("a").gt(0)).any());
    }

    @Test
    void mathematicalFunctionsMatchIndependentNumericResults() {
        for (var method : MathMethodEnum.values()) {
            double expected = switch (method) {
                case Abs -> Math.abs(0.5);
                case Signum -> 1;
                case Floor, Truncate -> 0;
                case Ceiling, Round -> 1;
                case Exp -> Math.exp(0.5);
                case Log -> Math.log(0.5);
                case Log10 -> Math.log10(0.5);
                case Pow -> Math.pow(0.5, 2);
                case Sqrt -> Math.sqrt(0.5);
                case Cos -> Math.cos(0.5);
                case Sin -> Math.sin(0.5);
                case Tan -> Math.tan(0.5);
                case Acos -> Math.acos(0.5);
                case Asin -> Math.asin(0.5);
                case Atan -> Math.atan(0.5);
                case Atan2 -> Math.atan2(0.5, 2);
            };
            double actual = source("math", new BigDecimal("0.5")).selectColumn(p -> new NumberTypeExpressionImpl<Double>(
                    p.getEntitySQLContext(), p.getTable(), null, fx -> fx.math(args -> {
                PropTypeColumn.columnFuncSelector(args, p.amount());
                if (method == MathMethodEnum.Pow || method == MathMethodEnum.Atan2) args.value(2);
            }, method), Double.class)).toList().get(0);
            assertEquals(expected, actual, 0.0000001, method.name());
        }
        assertDecimal("1.25", source("math", new BigDecimal("-1.25")).selectColumn(p -> p.amount().abs()).toList().get(0));
        assertDecimal("-1.3", source("math", new BigDecimal("-1.25")).selectColumn(p -> p.amount().round(1)).toList().get(0));
        assertDecimal("0.75", source("math", BigDecimal.ONE)
                .selectColumn(p -> p.amount().add(2).multiply(3).subtract(6).divide(4)).toList().get(0));
        assertNull(source("math", null).selectColumn(p -> p.amount().abs()).toList().get(0));
    }

    @Test
    void legacyRoundFactoryKeepsTableOwnersNullsAndNegativeScale() {
        record Sample(String value, int scale, String expected) {
        }
        for (boolean explicitOwner : List.of(false, true)) {
            for (var sample : List.of(new Sample("12.35", 1, "12.4"), new Sample("-1.25", 1, "-1.3"),
                    new Sample("125", -1, "130"), new Sample(null, 2, null))) {
                var value = sample.value() == null ? null : new BigDecimal(sample.value());
                var rounded = source("legacy", value).selectColumn(p -> new NumberTypeExpressionImpl<BigDecimal>(
                        p.getEntitySQLContext(), p.getTable(), null,
                        fx -> explicitOwner ? fx.round(() -> p.getTable(), "amount", sample.scale()) : fx.round("amount", sample.scale()),
                        BigDecimal.class));
                assertEquals(rounded.toSQL(), rounded.cloneQueryable().toSQL());
                var actual = rounded.toList().get(0);
                if (sample.expected() == null) assertNull(actual);
                else assertDecimal(sample.expected(), actual);
            }
        }
    }

    @Test
    @SuppressWarnings("deprecation")
        // 保留旧 equalsWith 工厂的兼容性验证。
    void booleanFactoriesRetainValuesAndPredicateComposition() {
        assertEquals(Boolean.TRUE, source("a", BigDecimal.ONE).selectColumn(p -> p.name().equalsWith("a")).toList().get(0));
        assertEquals(Boolean.FALSE, source(null, BigDecimal.ONE).selectColumn(p -> p.name().equalsWith("a")).toList().get(0));
        assertEquals(Boolean.TRUE, source("a", BigDecimal.ONE).selectColumn(p -> new BooleanTypeExpressionImpl<Boolean>(
                p.getEntitySQLContext(), p.getTable(), null, fx -> fx.booleanConstantSQLFunction(true), Boolean.class)).toList().get(0));
        for (BigDecimal value : Arrays.asList(BigDecimal.ONE, BigDecimal.ZERO, null)) {
            var predicate = source("a", value).selectColumn(p -> new BooleanTypeExpressionImpl<Boolean>(
                    p.getEntitySQLContext(), p.getTable(), null, fx -> fx.booleanSQLFunction("{0} > {1}", args -> {
                PropTypeColumn.columnFuncSelector(args, p.amount());
                args.value(BigDecimal.ZERO);
            }), Boolean.class));
            assertEquals(value == null ? null : value.signum() > 0, predicate.toList().get(0));
            assertEquals(predicate.toSQL(), predicate.cloneQueryable().toSQL());
        }
        assertTrue(source("a", BigDecimal.ONE).where(p -> p.name().toUpper().notLike("z")).any());
        assertEquals("中文", source("a", BigDecimal.ONE).selectColumn(p -> p.expression().constant("中文")).toList().get(0));
    }

    private EntityQueryable<TestProductProxy, TestProduct> source(String text, BigDecimal number) {
        // 真实执行派生表即可验证表达式，不为只读函数测试创建和清理实体表。
        return query.queryable("SELECT 1 AS \"id\",CAST(? AS NVARCHAR(255)) AS \"name\","
                + "CAST(? AS DECIMAL(19,4)) AS \"amount\" FROM DUMMY", TestProduct.class, Arrays.asList(text, number));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
