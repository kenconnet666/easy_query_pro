package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.Collections;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;

import static org.junit.jupiter.api.Assertions.*;

class TemporalTruncationDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void repeatedDateOperandsHaveIndependentBindings(DecimalDslTest.Backend backend) {
        LocalDateTime input = LocalDateTime.of(2024, 5, 31, 12, 34, 56, 987654000);
        for (TruncationUnit unit : TruncationUnit.values()) {
            Query<LocalDateTime> query = backend.client().queryable(TestEvent.class).selectColumn(e ->
                    ProDsl.of(e).truncateDateTime(e.expression().constant(input), unit));
            ToSQLResult result = query.toSQLResult();
            int count = backend.dialect() == SqlDialect.POSTGRESQL ? 1 : repeatedOperands(unit, backend.dialect());
            assertEquals(Collections.nCopies(count, input), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()), unit.name());
            assertEquals(count, result.getSQL().chars().filter(c -> c == '?').count(), result.getSQL());
            assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
        }
    }

    private static int repeatedOperands(TruncationUnit unit, SqlDialect dialect) {
        switch (unit) {
            case YEAR: case DAY: return 1;
            case MONTH: case ISO_WEEK: case HOUR: return 2;
            case QUARTER: return dialect == SqlDialect.HANA ? 2 : 3;
            case MINUTE: return 3;
            case SECOND: return 4;
            default: throw new IllegalArgumentException(unit.name());
        }
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void dateOnlyRejectsTimeUnitsBeforeSqlAndRetainsDateOperators(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        for (TruncationUnit unit : new TruncationUnit[]{TruncationUnit.HOUR, TruncationUnit.MINUTE, TruncationUnit.SECOND}) {
            assertThrows(IllegalArgumentException.class, () -> client.queryable(TestEvent.class)
                    .selectColumn(e -> ProDsl.of(e).truncateDate(e.eventDate(), unit)));
        }
        Query<Integer> query = client.queryable(TestEvent.class).selectColumn(e -> ProDsl.of(e).truncateDate(e.eventDate(), TruncationUnit.MONTH).year());
        assertEquals(query.toSQL(), query.cloneQueryable().toSQL());
    }
}
