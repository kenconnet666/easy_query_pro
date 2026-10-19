package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.StringTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.BooleanTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the upstream expression bridge before fixing the public enhancement API. */
class ExpressionInteropTest {
    @ParameterizedTest
    @MethodSource("databases")
    void typedExpressionComposesWithExistingFunctionsOrderingProjectionAndClone(DatabaseConfiguration database) {
        var client = TestClients.offline(database);
        var query = client.queryable(TestProduct.class)
                .where(p -> nullIf(p.name(), "hidden").toUpper().eq("VISIBLE"))
                .orderBy(p -> nullIf(p.name(), "sort-null").asc())
                .selectColumn(p -> nullIf(p.name(), "O'Reilly 中文").toUpper());
        var first = query.toSQLResult();
        assertTrue(first.getSQL().contains("UPPER(NULLIF("), first.getSQL());
        assertFalse(first.getSQL().contains("O'Reilly"));
        assertEquals(List.of("O'Reilly 中文", "hidden", "VISIBLE", "sort-null"),
                first.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        var copy = query.cloneQueryable().toSQLResult();
        assertEquals(first.getSQL(), copy.getSQL());
        assertEquals(first.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList(),
                copy.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
    }

    @ParameterizedTest
    @MethodSource("databases")
    void constructingBooleanValuesDoesNotAppendAPredicate(DatabaseConfiguration database) {
        var client = TestClients.offline(database);
        var untouched = client.queryable(TestProduct.class).where(p -> isNull(p.name()));
        assertFalse(untouched.toSQL().contains(" WHERE "));
        var filtered = client.queryable(TestProduct.class).where(p -> isNull(p.name()).eq(true));
        assertTrue(filtered.toSQL().contains(" WHERE "));
        var projected = client.queryable(TestProduct.class).selectColumn(p -> isNull(p.name())).toSQL();
        assertTrue(projected.contains("CASE WHEN"), projected);
        assertFalse(projected.contains(" WHERE "));
    }

    private static StringTypeExpression<String> nullIf(PropTypeColumn<String> column, String value) {
        return new StringTypeExpressionImpl<>(column.getEntitySQLContext(), column.getTable(), null,
                fx -> fx.anySQLFunction("NULLIF({0},{1})", args -> {
                    PropTypeColumn.columnFuncSelector(args, column);
                    args.value(value);
                }), String.class);
    }

    private static BooleanTypeExpression<Boolean> isNull(PropTypeColumn<?> column) {
        return new BooleanTypeExpressionImpl<>(column.getEntitySQLContext(), column.getTable(), null,
                fx -> fx.anySQLFunction("(CASE WHEN {0} IS NULL THEN TRUE ELSE FALSE END)",
                        args -> PropTypeColumn.columnFuncSelector(args, column)), Boolean.class);
    }

    static Stream<DatabaseConfiguration> databases() {
        return Stream.of(new MySQLDatabaseConfiguration(), new PgSQLDatabaseConfiguration(), new HanaDatabaseConfiguration());
    }
}
