package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaCalculationView;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaInput;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaTableFunction;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaViewRowProxy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.math.BigDecimal;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaViewRow;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class HanaSourceDslTest {
    static Stream<DecimalDslTest.Backend> hana() {
        return DecimalDslTest.backends().filter(backend -> backend.dialect() == SqlDialect.HANA);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void calculationSourceUsesBoundValuesAndTheOriginalQueryable(DecimalDslTest.Backend backend) {
        HanaInput<String> name = HanaInput.required("P_NAME", String.class);
        HanaInput<Integer> id = HanaInput.optional("P_ID", Integer.class);
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> view = HanaCalculationView.of("report schema", "VIEW\"quoted", HanaViewRowProxy::createTable, name, id);
        EasyQueryClient client = backend.client().getEasyQueryClient();
        if (backend.dialect() != SqlDialect.HANA) {
            assertThrows(IllegalArgumentException.class, () -> view.query(client, name.bind("value")));
            return;
        }
        EntityQueryable<HanaViewRowProxy, HanaViewRow> query = view.query(client, id.bind(7), name.bind("O'Reilly 中😀")).where(r -> r.id().gt(0));
        ToSQLResult result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("\"report schema\".\"VIEW\"\"quoted\""), result.getSQL());
        assertTrue(result.getSQL().contains("PLACEHOLDER.\"$$P_NAME$$\" => ?,PLACEHOLDER.\"$$P_ID$$\" => ?"), result.getSQL());
        assertFalse(result.getSQL().contains("O'Reilly"), result.getSQL());
        assertEquals(Arrays.asList("O'Reilly 中😀", 7, 0), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
        HanaInput<String> quotedInput = HanaInput.required("P\"NAME", String.class);
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> quotedView = HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, quotedInput);
        assertTrue(quotedView.query(client, quotedInput.bind("x")).toSQL().contains("PLACEHOLDER.\"$$P\"\"NAME$$\" => ?"));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void inputPresenceTypesAndProxyOwnershipAreValidated(DecimalDslTest.Backend backend) {
        HanaInput<String> name = HanaInput.required("P_NAME", String.class);
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> view = HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, name);
        EasyQueryClient client = backend.client().getEasyQueryClient();
        assertThrows(IllegalArgumentException.class, () -> view.query(client));
        assertThrows(IllegalArgumentException.class, () -> view.query(client, name.bind("a"), name.bind("b")));
        assertThrows(IllegalArgumentException.class, () -> view.query(client, HanaInput.required("P_NAME", String.class).bind("a")));
        assertThrows(IllegalArgumentException.class, () -> name.bind(null));
        assertThrows(IllegalArgumentException.class, () -> HanaInput.required("$$P_NAME$$", String.class));
        assertThrows(IllegalArgumentException.class, () -> HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, name, name));
        HanaViewRowProxy proxy = HanaViewRowProxy.createTable();
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> reused = HanaCalculationView.of("REPORT", "VIEW", () -> proxy, name);
        reused.query(client, name.bind("a"));
        assertThrows(IllegalArgumentException.class, () -> reused.query(client, name.bind("b")));
        assertWrongRuntimeTypeIsRejected(name);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertWrongRuntimeTypeIsRejected(HanaInput<String> name) {
        HanaInput raw = name;
        assertThrows(IllegalArgumentException.class, () -> raw.bind(1));
    }

    @Test
    void nullableDeclarationPreservesCustomNonNullValidation() {
        AtomicInteger encodings = new AtomicInteger();
        class PositiveInput extends HanaInput<Integer> {
            PositiveInput() {
                super("P_ID", Integer.class, true, false);
            }

            @Override
            protected void validate(Integer value) {
                super.validate(value);
                if (value < 1) throw new IllegalArgumentException("P_ID must be positive");
            }

            @Override
            protected Object toJdbcValue(Integer value) {
                encodings.incrementAndGet();
                return value.toString();
            }
        }
        HanaInput<Integer> input = new PositiveInput().nullable();
        assertNull(input.bind(null).value());
        assertEquals(1, input.bind(1).value());
        assertThrows(IllegalArgumentException.class, () -> input.bind(0));
        assertSame(input, input.nullable());
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> view = HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, input);
        EasyQueryClient client = hana().findFirst().get().client().getEasyQueryClient();
        EntityQueryable<HanaViewRowProxy, HanaViewRow> query = view.query(client, input.bind(1));
        assertEquals("1", query.cloneQueryable().toSQLResult().getSqlContext().getParameters().get(0).getValue());
        assertEquals(1, encodings.get());
        query.cloneQueryable().toSQL();
        assertNull(view.query(client, input.bind(null)).toSQLResult().getSqlContext().getParameters().get(0).getValue());
        assertEquals(1, encodings.get(), "Cloning and explicit NULL must not invoke the encoder");
    }

    @ParameterizedTest
    @MethodSource("hana")
    void multiValueListsUseOneBindingAndRejectAmbiguousEmptyInputs(DecimalDslTest.Backend backend) {
        EasyQueryClient client = backend.client().getEasyQueryClient();
        HanaInput<List<String>> texts = HanaInput.requiredTextList("P_NAMES");
        assertThrows(IllegalArgumentException.class, () -> texts.bind(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> texts.bind(Arrays.asList("a", null)));
        assertThrows(IllegalArgumentException.class, () -> texts.bind(null));
        assertThrows(UnsupportedOperationException.class, texts::nullable);
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> view = HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, texts);
        ToSQLResult result = view.query(client, texts.bind(Arrays.asList("O'Reilly, 中文", "A\"B", ""))).toSQLResult();
        assertEquals(Arrays.asList("'O''Reilly, 中文','A\"B',''"), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertFalse(result.getSQL().contains("O'Reilly"));
        ArrayList<String> mutableValues = new ArrayList<>(Arrays.asList("before"));
        EntityQueryable<HanaViewRowProxy, HanaViewRow> snapshot = view.query(client, texts.bind(mutableValues));
        mutableValues.set(0, "after");
        assertEquals("'before'", snapshot.cloneQueryable().toSQLResult().getSqlContext().getParameters().get(0).getValue());
        HanaInput<List<BigDecimal>> numbers = HanaInput.requiredNumericList("P_VALUES", BigDecimal.class);
        ToSQLResult numeric = HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, numbers)
                .query(client, numbers.bind(Arrays.asList(new BigDecimal("1E+3"), new BigDecimal("-1.25")))).toSQLResult();
        assertEquals("1000,-1.25", numeric.getSqlContext().getParameters().get(0).getValue());
        assertThrows(IllegalArgumentException.class, () -> HanaInput.requiredNumericList("P_VALUES", Double.class));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void omissionAndExplicitNullRemainDifferent(DecimalDslTest.Backend backend) {
        HanaInput<String> name = HanaInput.optional("P_NAME", String.class).nullable();
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> view = HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, name);
        EasyQueryClient client = backend.client().getEasyQueryClient();
        ToSQLResult omitted = view.query(client).toSQLResult();
        assertFalse(omitted.getSQL().contains("PLACEHOLDER"));
        ToSQLResult explicit = view.query(client, name.bind(null)).toSQLResult();
        assertTrue(explicit.getSQL().contains("PLACEHOLDER"));
        assertEquals(Arrays.asList((Object) null), explicit.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void tableFunctionUsesNamedBindingsAndKeepsMissingMiddleParameters(DecimalDslTest.Backend backend) {
        HanaInput<String> name = HanaInput.required("P_NAME", String.class);
        HanaInput<Integer> middle = HanaInput.optional("P_MIN_ID", Integer.class);
        HanaInput<BigDecimal> amount = HanaInput.optional("P_MIN_AMOUNT", BigDecimal.class);
        HanaTableFunction<HanaViewRowProxy, HanaViewRow> function = HanaTableFunction.of("report schema", "FUNCTION\"quoted",
                HanaViewRowProxy::createTable, name, middle, amount);
        EasyQueryClient client = backend.client().getEasyQueryClient();
        if (backend.dialect() != SqlDialect.HANA) {
            assertThrows(IllegalArgumentException.class, () -> function.query(client, name.bind("value")));
            return;
        }
        EntityQueryable<HanaViewRowProxy, HanaViewRow> query = function.query(client, amount.bind(BigDecimal.TEN), name.bind("O'Reilly"))
                .where(r -> r.id().gt(0));
        ToSQLResult result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("\"report schema\".\"FUNCTION\"\"quoted\" (\"P_NAME\" => ?,\"P_MIN_AMOUNT\" => ?)"), result.getSQL());
        assertFalse(result.getSQL().contains("PLACEHOLDER"));
        assertFalse(result.getSQL().contains("O'Reilly"));
        assertEquals(Arrays.asList("O'Reilly", BigDecimal.TEN, 0),
                result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
        assertThrows(IllegalArgumentException.class, () -> function.query(client));
        assertThrows(IllegalArgumentException.class, () -> function.query(client, name.bind("a"), name.bind("b")));
        assertThrows(IllegalArgumentException.class, () -> function.query(client, HanaInput.required("P_NAME", String.class).bind("a")));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void tableFunctionPreservesEmptyInvocationNullAndProxyOwnership(DecimalDslTest.Backend backend) {
        HanaInput<String> name = HanaInput.optional("P_NAME", String.class).nullable();
        HanaTableFunction<HanaViewRowProxy, HanaViewRow> function = HanaTableFunction.of("REPORT", "FUNCTION", HanaViewRowProxy::createTable, name);
        EasyQueryClient client = backend.client().getEasyQueryClient();
        ToSQLResult omitted = function.query(client).toSQLResult();
        assertTrue(omitted.getSQL().contains("\"REPORT\".\"FUNCTION\" ()"), omitted.getSQL());
        assertTrue(omitted.getSqlContext().getParameters().isEmpty());
        ToSQLResult explicit = function.query(client, name.bind(null)).toSQLResult();
        assertTrue(explicit.getSQL().contains("\"P_NAME\" => ?"));
        assertEquals(Arrays.asList((Object) null), explicit.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        HanaViewRowProxy proxy = HanaViewRowProxy.createTable();
        HanaTableFunction<HanaViewRowProxy, HanaViewRow> reused = HanaTableFunction.of("REPORT", "FUNCTION", () -> proxy, name);
        reused.query(client);
        assertThrows(IllegalArgumentException.class, () -> reused.query(client));
        assertThrows(IllegalArgumentException.class, () -> HanaTableFunction.of("REPORT", "FUNCTION", HanaViewRowProxy::createTable, name, name));
    }
}
