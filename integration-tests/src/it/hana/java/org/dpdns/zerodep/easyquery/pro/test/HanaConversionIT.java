package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.api.database.CodeFirstCommandTxArg;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.CodecRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaConversionIT {
    @Test
    void generatedKeysUseTheSameValueConverterAsNormalEntityReads() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("CONVERT_KEY")) {
            var query = client("one:");
            createTable(query, schema.name());
            var first = record("first");
            var second = record("second");
            assertEquals(2L, query.insertable(List.of(first, second)).asSchema(s -> schema.name()).executeRows(true));
            assertEquals(new CodecRecord.RowId(1), first.getId());
            assertEquals(new CodecRecord.RowId(2), second.getId());
            var rows = query.queryable(CodecRecord.class).asSchema(s -> schema.name()).orderBy(row -> row.id().asc()).toList();
            assertEquals(first.getId(), rows.get(0).getId());
            assertEquals(second.getId(), rows.get(1).getId());
            var numericId = query.queryable(CodecRecord.class).asSchema(s -> schema.name()).orderBy(row -> row.id().asc())
                    .selectColumn(row -> ProDsl.of(row).nativeCast(row.id(), CommonTypes.decimal(19, 0))).toList();
            assertEquals(List.of(new BigDecimal("1"), new BigDecimal("2")), numericId);
        }
    }

    @Test
    void converterValuesNullsDtosAndClientIsolationReuseTheUpstreamPipeline() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("CONVERT_VALUE")) {
            var firstClient = client("first:");
            var otherClient = client("other:");
            createTable(firstClient, schema.name());
            var first = record("O'Reilly 中文");
            first.setDocument(new CodecRecord.Label("中😀".repeat(3000)));
            first.setPrice(new BigDecimal("19.99"));
            first.setPlain("ordinary");
            firstClient.insertable(first).asSchema(s -> schema.name()).executeRows(false);
            var absent = new CodecRecord();
            firstClient.insertable(absent).asSchema(s -> schema.name()).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows(false);
            var other = record(first.getLabel().value());
            otherClient.insertable(other).asSchema(s -> schema.name()).executeRows(false);
            var loaded = firstClient.queryable(CodecRecord.class).asSchema(s -> schema.name()).where(row -> row.label().eq(first.getLabel())).singleNotNull();
            assertEquals(first.getLabel(), loaded.getLabel());
            assertEquals(first.getDocument(), loaded.getDocument());
            assertEquals(first.getPlain(), loaded.getPlain());
            assertEquals(1L, otherClient.queryable(CodecRecord.class).asSchema(s -> schema.name()).where(row -> row.label().eq(other.getLabel())).count());
            var dto = firstClient.queryable(CodecRecord.class).asSchema(s -> schema.name()).where(row -> row.label().eq(first.getLabel())).select(CodecRecord.View.class).singleNotNull();
            assertEquals(loaded.getId(), dto.getId());
            assertEquals(first.getLabel(), dto.getLabel());
            var nullRow = firstClient.queryable(CodecRecord.class).asSchema(s -> schema.name()).where(row -> row.label().isNull()).singleNotNull();
            assertNull(nullRow.getLabel());
            assertNull(nullRow.getDocument());
            var encoded = firstClient.queryable(CodecRecord.class).asSchema(s -> schema.name()).where(row -> row.label().eq(first.getLabel()))
                    .selectColumn(row -> row.label().asStr()).toList();
            assertEquals(List.of("first:" + first.getLabel().value()), encoded);
            assertEquals(List.of("first:" + first.getLabel().value() + "!"), firstClient.queryable(CodecRecord.class).asSchema(s -> schema.name())
                    .where(row -> row.label().eq(first.getLabel())).selectColumn(row -> row.label().asStr().concat("!")).toList());
            loaded.setLabel(new CodecRecord.Label("changed"));
            firstClient.updatable(loaded).asSchema(s -> schema.name()).executeRows();
            assertEquals(1L, firstClient.queryable(CodecRecord.class).asSchema(s -> schema.name()).where(row -> row.label().eq(loaded.getLabel())).count());
        }
    }

    @Test
    void sqlConvertersComposeWithParameterizedWritesPredicatesAndProjections() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("CONVERT_SQL")) {
            var query = client("p:");
            createTable(query, schema.name());
            var row = record("priced");
            row.setPrice(new BigDecimal("19.99"));
            var insert = query.insertable(row).asSchema(s -> schema.name());
            String insertSql = insert.toSQL(row);
            insert.executeRows(false);
            var client = query.getEasyQueryClient();
            var stored = client.sqlQuery("SELECT \"price\" FROM " + schema.qualified("eqp_codec"), BigDecimal.class).get(0);
            assertEquals(0, new BigDecimal("1999").compareTo(stored), insertSql + "; stored=" + stored);
            var loaded = query.queryable(CodecRecord.class).asSchema(s -> schema.name()).where(value -> value.price().eq(new BigDecimal("19.99"))).singleNotNull();
            assertEquals(0, row.getPrice().compareTo(loaded.getPrice()));
            loaded.setPrice(new BigDecimal("23.45"));
            query.updatable(loaded).asSchema(s -> schema.name()).executeRows();
            assertEquals(0, new BigDecimal("2345").compareTo(client.sqlQuery("SELECT \"price\" FROM " + schema.qualified("eqp_codec"), BigDecimal.class).get(0)));
            assertEquals(0, new BigDecimal("23.45").compareTo(query.queryable(CodecRecord.class).asSchema(s -> schema.name()).selectColumn(value -> value.price()).toList().get(0)));
            assertEquals(0, new BigDecimal("23.45").compareTo(query.queryable(CodecRecord.class).asSchema(s -> schema.name())
                    .selectColumn(value -> ProDsl.of(value).nativeCast(value.price(), CommonTypes.decimal(19, 2))).singleNotNull()));
        }
    }

    private static DefaultEasyEntityQuery client(String prefix) throws SQLException {
        var query = new DefaultEasyEntityQuery(EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(HanaTestDatabase.dataSource()).useDatabaseConfigure(new HanaDatabaseConfiguration())
                .customConfigure(HanaExtensions::configure).build());
        var configuration = query.getEasyQueryClient().getRuntimeContext().getQueryConfiguration();
        configuration.applyValueConverter(new CodecRecord.IdConverter());
        configuration.applyValueConverter(new CodecRecord.LabelConverter(prefix));
        configuration.applyColumnValueSQLConverter(new CodecRecord.CentsConverter());
        return query;
    }

    private static void createTable(DefaultEasyEntityQuery query, String schema) {
        var client = query.getEasyQueryClient();
        var data = client.getRuntimeContext().getMigrationsSQLGenerator().parseEntity(CodecRecord.class);
        data.setSchema(schema);
        client.getDatabaseCodeFirst().createTableCommandByMigrationData(List.of(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
    }

    private static CodecRecord record(String label) {
        var row = new CodecRecord();
        row.setLabel(new CodecRecord.Label(label));
        return row;
    }
}
