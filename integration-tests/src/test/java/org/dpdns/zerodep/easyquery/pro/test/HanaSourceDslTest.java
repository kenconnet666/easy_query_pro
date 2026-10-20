package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaCalculationView;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaInput;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaViewRowProxy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HanaSourceDslTest {
    static Stream<DecimalDslTest.Backend> hana() {
        return DecimalDslTest.backends().filter(backend -> backend.dialect() == SqlDialect.HANA);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void calculationSourceUsesBoundValuesAndTheOriginalQueryable(DecimalDslTest.Backend backend) {
        var name = HanaInput.required("P_NAME", String.class);
        var id = HanaInput.optional("P_ID", Integer.class);
        var view = HanaCalculationView.of("report schema", "VIEW\"quoted", HanaViewRowProxy::createTable, name, id);
        var client = backend.client().getEasyQueryClient();
        if (backend.dialect() != SqlDialect.HANA) {
            assertThrows(IllegalArgumentException.class, () -> view.query(client, name.bind("value")));
            return;
        }
        var query = view.query(client, id.bind(7), name.bind("O'Reilly 中😀")).where(r -> r.id().gt(0));
        var result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("\"report schema\".\"VIEW\"\"quoted\""), result.getSQL());
        assertTrue(result.getSQL().contains("PLACEHOLDER.\"$$P_NAME$$\" => ?,PLACEHOLDER.\"$$P_ID$$\" => ?"), result.getSQL());
        assertFalse(result.getSQL().contains("O'Reilly"), result.getSQL());
        assertEquals(List.of("O'Reilly 中😀", 7, 0), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
        var quotedInput = HanaInput.required("P\"NAME", String.class);
        var quotedView = HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, quotedInput);
        assertTrue(quotedView.query(client, quotedInput.bind("x")).toSQL().contains("PLACEHOLDER.\"$$P\"\"NAME$$\" => ?"));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void inputPresenceTypesAndProxyOwnershipAreValidated(DecimalDslTest.Backend backend) {
        var name = HanaInput.required("P_NAME", String.class);
        var view = HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, name);
        var client = backend.client().getEasyQueryClient();
        assertThrows(IllegalArgumentException.class, () -> view.query(client));
        assertThrows(IllegalArgumentException.class, () -> view.query(client, name.bind("a"), name.bind("b")));
        assertThrows(IllegalArgumentException.class, () -> view.query(client, HanaInput.required("P_NAME", String.class).bind("a")));
        assertThrows(IllegalArgumentException.class, () -> name.bind(null));
        assertThrows(IllegalArgumentException.class, () -> HanaInput.required("$$P_NAME$$", String.class));
        assertThrows(IllegalArgumentException.class, () -> HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, name, name));
        var proxy = HanaViewRowProxy.createTable();
        var reused = HanaCalculationView.of("REPORT", "VIEW", () -> proxy, name);
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
        class PositiveInput extends HanaInput<Integer> {
            PositiveInput() {
                super("P_ID", Integer.class, true, false);
            }

            @Override
            protected void validate(Integer value) {
                super.validate(value);
                if (value < 1) throw new IllegalArgumentException("P_ID must be positive");
            }
        }
        var input = new PositiveInput().nullable();
        assertNull(input.bind(null).value());
        assertEquals(1, input.bind(1).value());
        assertThrows(IllegalArgumentException.class, () -> input.bind(0));
        assertSame(input, input.nullable());
    }

    @ParameterizedTest
    @MethodSource("hana")
    void omissionAndExplicitNullRemainDifferent(DecimalDslTest.Backend backend) {
        var name = HanaInput.optional("P_NAME", String.class).nullable();
        var view = HanaCalculationView.of("REPORT", "VIEW", HanaViewRowProxy::createTable, name);
        var client = backend.client().getEasyQueryClient();
        var omitted = view.query(client).toSQLResult();
        assertFalse(omitted.getSQL().contains("PLACEHOLDER"));
        var explicit = view.query(client, name.bind(null)).toSQLResult();
        assertTrue(explicit.getSQL().contains("PLACEHOLDER"));
        assertEquals(Arrays.asList((Object) null), explicit.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
    }
}
