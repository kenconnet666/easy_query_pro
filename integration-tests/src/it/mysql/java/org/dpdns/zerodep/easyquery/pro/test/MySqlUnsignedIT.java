package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.MySqlUnsignedRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MySqlUnsignedIT {
    private static final BigInteger MAX = new BigInteger("18446744073709551615");

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void unsignedStorageAndExpressionsRemainExact(boolean serverPrepared, boolean signedSubtraction) throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        source.setUseServerPrepStmts(serverPrepared);
        source.setSessionVariables("sql_mode='STRICT_ALL_TABLES" + (signedSubtraction ? ",NO_UNSIGNED_SUBTRACTION" : "") + "'");
        try (var fixture = MySqlTestDatabase.createTable(source, "eqp_unsigned_", "id INTEGER PRIMARY KEY, tiny_value " + MySqlTypes.unsignedTinyint().ddlType(SqlDialect.MYSQL)
                + ", small_value " + MySqlTypes.unsignedSmallint().ddlType(SqlDialect.MYSQL) + ", medium_value " + MySqlTypes.unsignedMediumint().ddlType(SqlDialect.MYSQL)
                + ", int_value " + MySqlTypes.unsignedInt().ddlType(SqlDialect.MYSQL) + ", big_value " + MySqlTypes.unsignedBigint().ddlType(SqlDialect.MYSQL))) {
            String table = fixture.name();
            var query = TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
            MySqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
            var full = new MySqlUnsignedRecord();
            full.setId(1);
            full.setTinyValue((short) 255);
            full.setSmallValue(65535);
            full.setMediumValue(16777215);
            full.setIntValue(4294967295L);
            full.setBigValue(MAX);
            var empty = new MySqlUnsignedRecord();
            empty.setId(2);
            empty.setTinyValue((short) 0);
            empty.setSmallValue(0);
            empty.setMediumValue(0);
            empty.setIntValue(0L);
            empty.setBigValue(BigInteger.ZERO);
            var absent = new MySqlUnsignedRecord();
            absent.setId(3);
            assertEquals(3L, query.insertable(List.of(full, empty, absent)).asTable(table).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows());
            var row = query.queryable(MySqlUnsignedRecord.class).asTable(table).where(p -> p.id().eq(1)).singleNotNull();
            assertEquals(full.getTinyValue(), row.getTinyValue());
            assertEquals(full.getSmallValue(), row.getSmallValue());
            assertEquals(full.getMediumValue(), row.getMediumValue());
            assertEquals(full.getIntValue(), row.getIntValue());
            assertEquals(MAX, row.getBigValue());
            assertNull(query.queryable(MySqlUnsignedRecord.class).asTable(table).where(p -> p.id().eq(3)).singleNotNull().getBigValue());
            verifyExpressions(query, table, signedSubtraction);
            assertThrows(RuntimeException.class, () -> query.updatable(MySqlUnsignedRecord.class).asTable(table).setColumns(p -> p.intValue().set(4294967296L)).where(p -> p.id().eq(1)).executeRows());
            assertEquals(4294967295L, query.queryable(MySqlUnsignedRecord.class).asTable(table).where(p -> p.id().eq(1)).singleNotNull().getIntValue());
            String aggregateSql = "SELECT SUM(big_value) FROM `" + table + "`";
            var baseline = TestClients.configured(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
            List<?> raw = baseline.getEasyQueryClient().sqlQuery(aggregateSql, BigInteger.class);
            assertInstanceOf(BigDecimal.class, raw.get(0), "3.2.17 has no exact BigInteger result handler");
            assertEquals(MAX, query.getEasyQueryClient().sqlQuery(aggregateSql, BigInteger.class).get(0));
            assertThrows(RuntimeException.class, () -> query.getEasyQueryClient().sqlQuery("SELECT 1.5", BigInteger.class));
        }
    }

    private static void verifyExpressions(DefaultEasyEntityQuery query, String table, boolean signedSubtraction) {
        var selected = query.queryable(MySqlUnsignedRecord.class).asTable(table).where(p -> p.id().eq(1)).select(p -> {
            var mysql = MySqlDsl.of(p);
            var common = ProDsl.of(p);
            return Select.DRAFT.of(mysql.value((short) 255, MySqlTypes.unsignedTinyint()), mysql.value(65535, MySqlTypes.unsignedSmallint()),
                    mysql.value(16777215, MySqlTypes.unsignedMediumint()), mysql.value(4294967295L, MySqlTypes.unsignedInt()),
                    common.value(MAX, MySqlTypes.unsignedBigint()), common.nativeCast(common.value("-1", CommonTypes.unicodeText(10)), MySqlTypes.unsignedBigint()));
        }).singleNotNull();
        assertEquals((short) 255, selected.getValue1());
        assertEquals(65535, selected.getValue2());
        assertEquals(16777215, selected.getValue3());
        assertEquals(4294967295L, selected.getValue4());
        assertEquals(MAX, selected.getValue5(), "Typed unsigned maximum parameter");
        assertEquals(MAX, selected.getValue6(), "Native negative-to-unsigned CAST");
        var difference = query.queryable(MySqlUnsignedRecord.class).asTable(table).where(p -> p.id().eq(1)).selectColumn(p ->
                ProDsl.of(p).value(BigInteger.ONE, MySqlTypes.unsignedBigint()).subtract(BigInteger.TWO));
        if (signedSubtraction) assertEquals(BigInteger.valueOf(-1), difference.singleNotNull());
        else assertThrows(RuntimeException.class, difference::toList);
        assertNull(query.queryable(MySqlUnsignedRecord.class).asTable(table).where(p -> p.id().eq(1))
                .selectColumn(p -> ProDsl.of(p).nullValue(MySqlTypes.unsignedBigint())).toList().get(0));
    }
}
