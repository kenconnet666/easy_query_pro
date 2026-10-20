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

import static org.junit.jupiter.api.Assertions.*;

class TemporalTruncationDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void repeatedDateOperandsHaveIndependentBindings(DecimalDslTest.Backend backend) {
        var input = LocalDateTime.of(2024, 5, 31, 12, 34, 56, 987654000);
        for (var unit : TruncationUnit.values()) {
            var query = backend.client().queryable(TestEvent.class).selectColumn(e ->
                    ProDsl.of(e).truncateDateTime(e.expression().constant(input), unit));
            var result = query.toSQLResult();
            int count = backend.dialect() == SqlDialect.POSTGRESQL ? 1 : switch (unit) {
                case YEAR, DAY -> 1;
                case MONTH, ISO_WEEK, HOUR -> 2;
                case QUARTER -> backend.dialect() == SqlDialect.HANA ? 2 : 3;
                case MINUTE -> 3;
                case SECOND -> 4;
            };
            assertEquals(Collections.nCopies(count, input), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList(), unit.name());
            assertEquals(count, result.getSQL().chars().filter(c -> c == '?').count(), result.getSQL());
            assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
        }
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void dateOnlyRejectsTimeUnitsBeforeSqlAndRetainsDateOperators(DecimalDslTest.Backend backend) {
        var client = backend.client();
        for (var unit : new TruncationUnit[]{TruncationUnit.HOUR, TruncationUnit.MINUTE, TruncationUnit.SECOND}) {
            assertThrows(IllegalArgumentException.class, () -> client.queryable(TestEvent.class)
                    .selectColumn(e -> ProDsl.of(e).truncateDate(e.eventDate(), unit)));
        }
        var query = client.queryable(TestEvent.class).selectColumn(e -> ProDsl.of(e).truncateDate(e.eventDate(), TruncationUnit.MONTH).year());
        assertEquals(query.toSQL(), query.cloneQueryable().toSQL());
    }
}
