package org.dpdns.zerodep.easyquery.pro.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DslCompilationTest {
    @TempDir
    Path directory;

    @Test
    void numericTypeAndValueRemainStaticallyLinkedForConsumers() throws IOException {
        compile(true, "NumberTypeExpression<BigDecimal> value = d.value(BigDecimal.ONE, CommonTypes.decimal(9, 2)); value.multiply(100).gt(BigDecimal.ZERO);");
        compile(false, "d.value(\"wrong type\", CommonTypes.decimal(9, 2));");
        compile(false, "NumberTypeExpression<Long> value = d.nullValue(CommonTypes.decimal(9, 2));");
        compile(false, "StringTypeExpression<String> value = d.nullValue(CommonTypes.decimal(9, 2));");
        compile(false, "StorageType<BigDecimal> type = CommonTypes.decimal(9, 2); d.nullValue(type);");
    }

    @Test
    void nativeNumericTypesStillUseTheCommonNumericExpressionFamily() throws IOException {
        compile(true, "NumberTypeExpression<BigDecimal> mysql = d.value(BigDecimal.ONE, MySqlTypes.decimal(65, 30)); NumberTypeExpression<BigDecimal> pg = d.nullValue(PgSqlTypes.numeric(2, -3)); NumberTypeExpression<BigDecimal> hana = d.nativeCast(p.amount(), HanaTypes.smallDecimal()); NumberTypeExpression<Short> tiny = d.value((short)255, HanaTypes.tinyint()); tiny.add((short)1);");
        compile(false, "d.value(255, HanaTypes.tinyint());");
        compile(false, "NumberTypeExpression<Double> n = d.nullValue(HanaTypes.floatingDecimal());");
        compile(false, "d.value(java.math.BigInteger.ONE, PgSqlTypes.numeric());");
        compile(false, "StringTypeExpression<String> n = d.nullValue(MySqlTypes.decimal(65, 0));");
    }

    @Test
    void hanaCharacterValuesUseStringOperationsWhileShorttextOnlyDescribesStorage() throws IOException {
        compile(true, "StringTypeExpression<String> alpha = d.nativeCast(p.name(), HanaTypes.alphanum(6)); alpha.toUpper(); d.value(\"中😀\", HanaTypes.nvarchar(20)).isNull(); StorageType<String> stored = HanaTypes.shortText(20);");
        compile(false, "d.nativeCast(p.name(), HanaTypes.shortText(20));");
        compile(false, "d.value(\"text\", HanaTypes.shortText(20));");
        compile(false, "NumberTypeExpression<Integer> text = d.value(\"ascii\", HanaTypes.varchar(20));");
    }

    @Test
    void nullSafeComparisonRequiresCompatibleOperandsAndReturnsBoolean() throws IOException {
        compile(true, "var type = CommonTypes.decimal(9, 2); BooleanTypeExpression<Boolean> result = d.nullSafeEquals(d.value(BigDecimal.ONE, type), d.nullValue(type)); result.eq(true);");
        compile(true, "d.nullSafeEqualsValue(d.nullValue(CommonTypes.decimal(9, 2)), null).eq(true); d.isDistinctFromValue(d.nullValue(CommonTypes.decimal(9, 2)), BigDecimal.ONE).eq(false);");
        compile(false, "StringTypeExpression<String> result = d.nullSafeEquals(d.nullValue(CommonTypes.decimal(9, 2)), d.nullValue(CommonTypes.decimal(9, 2)));");
        compile(false, "d.nullSafeEquals(d.nullValue(CommonTypes.decimal(9, 2)), d.nullValue(CommonTypes.decimal(9, 2)).asStr());");
    }

    @Test
    void nullIfRetainsTheSelectedScalarFamily() throws IOException {
        compile(true, "NumberTypeExpression<BigDecimal> number = d.nullIfValue(p.amount(), BigDecimal.ZERO); number.multiply(2); StringTypeExpression<String> text = d.nullIfValue(p.name(), null); text.toUpper();");
        compile(true, "DateTimeTypeExpression<java.time.LocalDate> day = d.nullIfValue(e.eventDate(), null); day.year(); BooleanTypeExpression<Boolean> flag = d.nullIfValue(d.nullSafeEqualsValue(p.id(), 1), true); flag.eq(false);");
        compile(false, "StringTypeExpression<String> text = d.nullIfValue(p.amount(), BigDecimal.ZERO);");
        compile(false, "d.nullIf(p.amount(), p.id());");
        compile(false, "d.nullIfValue(e.eventDate(), \"wrong type\");");
    }

    @Test
    void calendarTruncationDoesNotAcceptAnInstantOrChangeTheDateType() throws IOException {
        compile(true, "DateTimeTypeExpression<java.time.LocalDate> day = d.truncateDate(e.eventDate(), TruncationUnit.MONTH); DateTimeTypeExpression<java.time.LocalDateTime> time = d.truncateDateTime(e.occurred(), TruncationUnit.HOUR); time.year();");
        compile(false, "d.truncateDateTime(e.eventDate(), TruncationUnit.DAY);");
        compile(false, "d.truncateDateTime(e.occurred().asAnyType(java.time.Instant.class), TruncationUnit.DAY);");
    }

    @Test
    void textDescriptorsReturnStringExpressionsAndRejectNumericValues() throws IOException {
        compile(true, "StringTypeExpression<String> text = d.value(\"hello\", CommonTypes.unicodeText(20)); text.toUpper(); d.nullValue(CommonTypes.unicodeText(20)).isNull();");
        compile(false, "NumberTypeExpression<BigDecimal> value = d.nativeCast(p.amount(), CommonTypes.unicodeText(20));");
        compile(false, "d.value(BigDecimal.ONE, CommonTypes.unicodeText(20));");
        compile(false, "d.value(\"hello\", CommonTypes.decimal(19, 2));");
    }

    @Test
    void jsonFragmentsRequireAnExplicitTextView() throws IOException {
        compile(true, "JsonFragmentExpression fragment = d.jsonAt(p.name(), JsonPath.root().key(\"field\")); fragment.asText().toUpper(); d.jsonBooleanAt(p.name(), JsonPath.root()).eq(true);");
        compile(false, "d.jsonAt(p.name(), JsonPath.root()).toUpper();");
        compile(false, "NumberTypeExpression<Integer> number = d.jsonAt(p.name(), JsonPath.root());");
    }

    @Test
    void postgresTypesSelectDifferentApisAndRetainBusinessMappings() throws IOException {
        compile(true, "PgJsonExpression<String> json = PgSqlDsl.of(p).value(JsonInput.encoded(\"{}\"), PgSqlTypes.json()); PgJsonbExpression<String> jsonb = json.toJsonb(); jsonb.containsJson(JsonInput.jsonNull()).eq(true);");
        compile(true, "PgJsonbExpression<org.dpdns.zerodep.easyquery.pro.test.entity.JsonPayload> jsonb = PgSqlDsl.of(j).view(j.payload(), PgSqlTypes.jsonb()); j.payload().set(jsonb.setPath(java.util.List.of(\"name\"), JsonInput.encoded(\"\\\"new\\\"\"), true));");
        compile(false, "PgSqlDsl.of(p).value(JsonInput.encoded(\"{}\"), PgSqlTypes.json()).containsJson(JsonInput.jsonNull());");
        compile(false, "PgSqlDsl.of(p).value(\"{}\", PgSqlTypes.jsonb());");
        compile(false, "PgJsonbExpression<String> jsonb = PgSqlDsl.of(p).value(JsonInput.encoded(\"{}\"), PgSqlTypes.jsonb()).asText();");
    }

    @Test
    void mysqlNativeJsonKeepsBusinessTypesAndDoesNotAcceptPlainText() throws IOException {
        compile(true, "MySqlJsonExpression<String> json = MySqlDsl.of(p).value(JsonInput.encoded(\"{}\"), MySqlTypes.json()); json.containsAllPaths(JsonPath.root()).eq(true); json.setPath(JsonPath.root(), JsonInput.jsonNull()).asText().toUpper();");
        compile(true, "MySqlJsonExpression<org.dpdns.zerodep.easyquery.pro.test.entity.JsonPayload> json = MySqlDsl.of(m).view(m.payload(), MySqlTypes.json()); m.payload().set(json.setPath(JsonPath.root().key(\"count\"), JsonInput.encoded(\"3\")));");
        compile(false, "MySqlDsl.of(p).value(\"{}\", MySqlTypes.json());");
        compile(false, "MySqlDsl.of(p).value(JsonInput.jsonNull(), MySqlTypes.json()).setPath(JsonPath.root(), \"null\");");
        compile(false, "MySqlDsl.of(p).value(JsonInput.jsonNull(), MySqlTypes.json()).toUpper();");
        compile(false, "MySqlDsl.of(p).value(JsonInput.jsonNull(), PgSqlTypes.jsonb());");
    }

    @Test
    void sqlArrayOperationsKeepElementsDistinctFromTheirWholeColumnMapping() throws IOException {
        compile(true, "var pg = PgSqlDsl.of(p); PgArrayExpression<Integer, PgArrayValue<Integer>> a = pg.value(java.util.List.of(1, 2), PgSqlTypes.array(Integer.class)); AnyTypeExpression<Integer> element = a.elementAtSqlIndex(1); BooleanTypeExpression<Boolean> matched = a.anyEq(p.id()); NumberTypeExpression<Integer> total = a.cardinality();");
        compile(true, "PgArrayExpression<String, java.util.List<String>> a = PgSqlDsl.of(aRecord).view(aRecord.labels(), PgSqlTypes.array(String.class)); a.overlapsValues(java.util.List.of(\"one\")).eq(true); aRecord.labels().set(a);");
        compile(false, "PgSqlDsl.of(p).value(java.util.List.of(\"wrong\"), PgSqlTypes.array(Integer.class));");
        compile(false, "PgSqlDsl.of(p).value(java.util.List.of(1), PgSqlTypes.array(Integer.class)).anyEqValue(\"wrong\");");
        compile(false, "PgSqlDsl.of(p).value(java.util.List.of(1), PgSqlTypes.array(Integer.class)).overlaps(PgSqlDsl.of(p).value(java.util.List.of(\"wrong\"), PgSqlTypes.array(String.class)));");
        compile(false, "AnyTypeExpression<String> element = PgSqlDsl.of(p).value(java.util.List.of(1), PgSqlTypes.array(Integer.class)).elementAtSqlIndex(1);");
        compile(false, "PgSqlDsl.of(p).value(java.util.List.of(1), PgSqlTypes.array(Integer.class)).anyEq(p.name());");
    }

    @Test
    void intervalsRetainCalendarMeaningAndDateArithmeticReturnsATimestamp() throws IOException {
        compile(true, "PgIntervalExpression<PgInterval> span = PgSqlDsl.of(p).value(PgInterval.of(1, 2, 3), PgSqlTypes.interval()); DateTimeTypeExpression<java.time.LocalDateTime> result = span.addToDate(e.eventDate()); span.plus(span).equalTo(span).eq(false);");
        compile(false, "PgSqlDsl.of(p).value(java.time.Duration.ofHours(1), PgSqlTypes.interval());");
        compile(false, "DateTimeTypeExpression<java.time.LocalDate> day = PgSqlDsl.of(p).value(PgInterval.of(1, 0, 0), PgSqlTypes.interval()).addToDate(e.eventDate());");
        compile(false, "PgSqlDsl.of(p).value(PgInterval.of(1, 0, 0), PgSqlTypes.interval()).addToDateTime(e.eventDate());");
        compile(false, "PgSqlDsl.of(p).value(PgInterval.of(1, 0, 0), PgSqlTypes.interval()).plus(p.amount());");
    }

    @Test
    void unsignedStorageWidthsDoNotPretendToBeNarrowNativeCastTargets() throws IOException {
        compile(true, "NumberTypeExpression<java.math.BigInteger> n = d.nativeCast(p.id(), MySqlTypes.unsignedBigint()); NumberTypeExpression<Long> small = MySqlDsl.of(p).value(4294967295L, MySqlTypes.unsignedInt());");
        compile(false, "d.nativeCast(p.id(), MySqlTypes.unsignedInt());");
        compile(false, "MySqlDsl.of(p).value(256, MySqlTypes.unsignedTinyint());");
        compile(false, "NumberTypeExpression<Long> n = d.value(java.math.BigInteger.ONE, MySqlTypes.unsignedBigint());");
    }

    @Test
    void mysqlDurationCannotBeConfusedWithClockTimeOrCalendarIntervals() throws IOException {
        compile(true, "MySqlDurationExpression<java.time.Duration> elapsed = MySqlDsl.of(p).value(java.time.Duration.ofHours(27), MySqlTypes.durationTime(6)); NumberTypeExpression<BigDecimal> seconds = elapsed.toSeconds(); DateTimeTypeExpression<java.time.LocalDateTime> next = elapsed.addToDateTime(e.occurred());");
        compile(false, "MySqlDsl.of(p).value(java.time.LocalTime.NOON, MySqlTypes.durationTime(6));");
        compile(false, "MySqlDsl.of(p).value(PgInterval.of(1, 0, 0), MySqlTypes.durationTime(6));");
        compile(false, "DateTimeTypeExpression<java.time.LocalTime> time = MySqlDsl.of(p).value(java.time.Duration.ZERO, MySqlTypes.durationTime(6));");
    }

    @Test
    void hanaLobViewsExposeOnlySupportedOperationsAndExplicitScalarConversions() throws IOException {
        compile(true, "HanaTextLobExpression<String> text = HanaDsl.of(p).value(\"large text\", HanaTypes.nclob()); text.isSqlNull().eq(false); StringTypeExpression<String> scalar = text.asText(); scalar.toUpper(); AnyTypeExpression<byte[]> bytes = HanaDsl.of(p).value(new byte[]{1}, HanaTypes.blob()).sliceBytes(1, 1);");
        compile(false, "HanaDsl.of(p).value(\"large text\", HanaTypes.nclob()).toUpper();");
        compile(false, "StringTypeExpression<String> length = HanaDsl.of(p).value(\"large text\", HanaTypes.nclob()).utf16Length();");
        compile(false, "HanaDsl.of(p).value(\"large text\", HanaTypes.nclob()).eq(\"other\");");
        compile(false, "HanaDsl.of(p).value(\"large text\", HanaTypes.text());");
        compile(false, "StringTypeExpression<String> bytes = HanaDsl.of(p).value(new byte[]{1}, HanaTypes.blob()).sliceBytes(1, 1);");
    }

    @Test
    void hanaJsonTextUsesSharedReadersWithoutPretendingToBeJsonb() throws IOException {
        compile(true, "HanaJsonTextExpression<String> json = HanaDsl.of(p).value(JsonInput.encoded(\"{}\"), HanaTypes.jsonNclob()); StringTypeExpression<String> field = json.textAt(JsonPath.root().key(\"name\")); json.asText().toUpper();");
        compile(false, "HanaDsl.of(p).value(JsonInput.encoded(\"{}\"), HanaTypes.jsonNclob()).containsJson(JsonInput.jsonNull());");
        compile(false, "HanaDsl.of(p).value(JsonInput.encoded(\"{}\"), HanaTypes.jsonNclob()).setPath(JsonPath.root(), JsonInput.jsonNull());");
        compile(false, "PgJsonbExpression<String> json = HanaDsl.of(p).value(JsonInput.jsonNull(), HanaTypes.jsonNclob());");
    }

    @Test
    void extensionFactoriesRetainTheirDeclaredValueTypes() throws IOException {
        compile(true, "class Reports extends PgSqlDsl { Reports(ExpressionScope scope) { super(scope); } String marker() { return \"report\"; } } Reports report = ProDsl.of(p, Reports::new); report.marker(); report.nullIfValue(p.name(), \"\");");
        compile(true, "NumberTypeExpression<BigDecimal> result = d.nativeCast(p.id(), CommonTypes.decimal(10, 2), (scope, source, function) -> new com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl<BigDecimal>(scope.context(), source.getTable(), source.getValue(), function, BigDecimal.class));");
        compile(false, "ExpressionFactory<String, NumberTypeExpression<BigDecimal>> mismatched = (scope, source, function) -> new com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl<BigDecimal>(scope.context(), source.getTable(), source.getValue(), function, BigDecimal.class); d.view(p.name(), mismatched);");
        compile(false, "StringTypeExpression<String> invalid = d.nativeCast(p.id(), CommonTypes.decimal(10, 2), (scope, source, function) -> new com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl<String>(scope.context(), source.getTable(), source.getValue(), function, String.class));");
    }

    @Test
    void customWholeValueChainsKeepSelfButScalarResultsDoNot() throws IOException {
        compile(true, "var mysql = MySqlDsl.of(m); ReportJson<org.dpdns.zerodep.easyquery.pro.test.entity.JsonPayload> json = mysql.view(m.payload(), (scope, source, function) -> new ReportJson<>(scope, mysql, source, function)); json.setPath(JsonPath.root().key(\"count\"), JsonInput.encoded(\"7\")).removePath(JsonPath.root().key(\"absent\")).hasName().eq(true);");
        compile(true, "var pg = PgSqlDsl.of(p); ReportInterval<PgInterval> span = pg.view(pg.value(PgInterval.of(1, 0, 0), PgSqlTypes.interval()), ReportInterval::new); span.plus(span).minus(span).negated().isZero().eq(false);");
        compile(false, "var mysql = MySqlDsl.of(m); var json = mysql.view(m.payload(), (scope, source, function) -> new ReportJson<>(scope, mysql, source, function)); json.asText().hasName();");
        compile(false, "var pg = PgSqlDsl.of(p); var span = pg.view(pg.value(PgInterval.of(1, 0, 0), PgSqlTypes.interval()), ReportInterval::new); span.addToDate(e.eventDate()).isZero();");
    }

    @Test
    void windowFactoriesKeepSelfAndResultTypes() throws IOException {
        compile(true, "ReportWindow frame = d.window(ReportWindow::new).partitionBy(p.name()).orderBy(p.id()).rowsBetween(RowBoundary.unboundedPreceding(), RowBoundary.currentRow()).trailing(2); NumberTypeExpression<BigDecimal> result = d.over(p.amount().sum(), frame);");
        compile(true, "var frame = d.window(); NumberTypeExpression<BigDecimal> result = d.over(p.amount().sum(), frame, (scope, source, function) -> new com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl<BigDecimal>(scope.context(), source.getTable(), source.getValue(), function, BigDecimal.class));");
        compile(false, "d.over(p.amount().sum(), d.window(), (scope, source, function) -> new com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl<String>(scope.context(), source.getTable(), source.getValue(), function, String.class));");
        compile(true, "StringTypeExpression<String> result = d.overValue(p.name(), d.window().orderBy(p.id()), (scope, source, function) -> new com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl<String>(scope.context(), source.getTable(), source.getValue(), function, String.class)).firstValue();");
        compile(false, "NumberTypeExpression<Integer> invalid = d.overValue(p.name(), d.window().orderBy(p.id())).firstValue();");
    }

    @Test
    void rankingWindowsKeepLongResultsAndCustomSpecificationTypes() throws IOException {
        compile(true, "ReportWindow ordered = d.window(ReportWindow::new).orderBy(p.amount(), false); NumberTypeExpression<Long> rank = d.rank(ordered); NumberTypeExpression<Long> dense = d.denseRank(ordered); NumberTypeExpression<Long> row = d.rowNumber(ordered.orderBy(p.id()));");
        compile(true, "NumberTypeExpression<Long> custom = d.view(d.rank(d.window().orderBy(p.id())), (scope, source, function) -> new com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl<Long>(scope.context(), source.getTable(), source.getValue(), function, Long.class));");
        compile(false, "NumberTypeExpression<Integer> rank = d.rank(d.window().orderBy(p.id()));");
        compile(false, "StringTypeExpression<String> row = d.rowNumber(d.window().orderBy(p.id()));");
    }

    private void compile(boolean expected, String body) throws IOException {
        var source = directory.resolve("Consumer.java");
        Files.writeString(source, """
                import java.math.BigDecimal;
                import com.easy.query.core.proxy.extension.functions.type.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
                import org.dpdns.zerodep.easyquery.pro.dsl.ProGroups;
                import org.dpdns.zerodep.easyquery.pro.dsl.group.GroupingSet;
                import com.easy.query.core.proxy.sql.GroupKeys;
                import com.easy.query.core.proxy.sql.Select;
                import org.dpdns.zerodep.easyquery.pro.dsl.window.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.mysql.window.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.window.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.spi.*;
                import org.dpdns.zerodep.easyquery.pro.test.extension.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
                import org.dpdns.zerodep.easyquery.pro.dsl.json.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.mysql.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.mysql.query.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.hana.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.*;
                import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaViewRowProxy;
                import org.dpdns.zerodep.easyquery.pro.dsl.hana.lob.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.hana.json.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.type.*;
                import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
                import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestEventProxy;
                import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgJsonRecordProxy;
                import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.MySqlJsonRecordProxy;
                import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgArrayRecordProxy;
                final class Consumer { void use(ProDsl d, TestProductProxy p, TestEventProxy e, PgJsonRecordProxy j, MySqlJsonRecordProxy m, PgArrayRecordProxy aRecord,
                    com.easy.query.api.proxy.entity.select.EntityQueryable<TestProductProxy, org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct> q) { %s } }
                """.formatted(body));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Consumer compilation requires a JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var options = List.of("--release", "17", "-proc:none", "-d", directory.toString(), "-classpath",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
            boolean compiled = compiler.getTask(null, files, diagnostics, options, null,
                    files.getJavaFileObjects(source.toFile())).call();
            assertEquals(expected, compiled, () -> body + "\n" + diagnostics.getDiagnostics());
            if (!expected)
                assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
        }
    }

    @Test
    void statisticsRequireNumericSamplesAndReturnDoubleExpressions() throws IOException {
        compile(true, "com.easy.query.core.proxy.extension.functions.type.filter.NumberFilterTypeExpression<Double> population = d.variancePopulation(p.amount()); NumberTypeExpression<Double> filtered = population.filter(() -> p.id().gt(0)); NumberTypeExpression<Double> window = d.over(d.standardDeviationSample(p.amount()), d.window().orderBy(p.id()));");
        compile(false, "d.variancePopulation(p.name());");
        compile(false, "NumberTypeExpression<BigDecimal> invalid = d.standardDeviationPopulation(p.amount());");
    }

    @Test
    void nativeWindowFactoriesPreserveSelfAndRejectOtherIntervalTypes() throws IOException {
        compile(true, "var mysql = d.window(MySqlWindowSpec::create).orderBy(p.id()).rangeNumericBetween(RangeBoundary.preceding(1), RangeBoundary.currentRow()); var pg = d.window(PgWindowSpec::create).orderBy(p.amount()).rangeNumericBetween(RangeBoundary.preceding(BigDecimal.ONE), RangeBoundary.currentRow());");
        compile(true, "ReportPgWindow frame = d.window(ReportPgWindow::new).partitionBy(p.name()).orderBy(e.eventDate()).rangeIntervalBetween(RangeBoundary.preceding(PgInterval.of(1,0,0)),RangeBoundary.currentRow()).calendarDays(2);");
        compile(false, "PgSqlDsl.of(p).nativeWindow().rangeIntervalBetween(RangeBoundary.preceding(PgInterval.positiveInfinity()),RangeBoundary.currentRow());");
        compile(false, "MySqlDsl.of(p).nativeWindow().rangeIntervalBetween(RangeBoundary.preceding(PgInterval.of(0,1,0)),RangeBoundary.currentRow());");
        compile(false, "d.window(scope -> \"invalid\");");
    }

    @Test
    void reportGroupsKeepKeysSourceTuplesAndResultTypes() throws IOException {
        compile(true, "var grouped = q.groupBy(x -> GroupKeys.of(x.id(), x.name())); var report = ProGroups.rollup(grouped, g -> Select.DRAFT.of(g.key1(), g.key2(), ProGroups.mask(g, g.key1(), g.key2()))); Integer id = report.singleNotNull().getValue1(); String name = report.singleNotNull().getValue2(); Integer mask = report.singleNotNull().getValue3();");
        compile(true, "var grouped = q.leftJoin(q, (a,b) -> a.id().eq(b.id())).groupBy((a,b) -> GroupKeys.of(a.id(), b.name())); var report = PgGroups.groupingSets(grouped, g -> java.util.List.of(GroupingSet.of(g.key1()), GroupingSet.of(g.key2())), g -> Select.DRAFT.of(g.key1(), g.key2(), g.groupTable().t2.amount().sumBigDecimal())); BigDecimal total = report.singleNotNull().getValue3();");
        compile(false, "var grouped = q.groupBy(x -> GroupKeys.of(x.id())); var report = ProGroups.rollup(grouped, g -> Select.DRAFT.of(g.key1())); String wrong = report.singleNotNull().getValue1();");
    }

    @Test
    void hanaSourceInputsAndOutputProxyStayTyped() throws IOException {
        compile(true, "var name = HanaInput.required(\"P_NAME\", String.class); var view = HanaCalculationView.of(\"REPORT\", \"VIEW\", HanaViewRowProxy::createTable, name); BigDecimal amount = view.query(null, name.bind(\"text\")).singleNotNull().getAmount();");
        compile(false, "var name = HanaInput.required(\"P_NAME\", String.class); name.bind(7);");
        compile(false, "var view = HanaCalculationView.of(\"REPORT\", \"VIEW\", HanaViewRowProxy::createTable); String wrong = view.query(null).singleNotNull().getAmount();");
        compile(true, "var names = HanaInput.requiredTextList(\"P_NAMES\"); names.bind(java.util.List.of(\"a\", \"b\")); var ids = HanaInput.requiredNumericList(\"P_IDS\", Integer.class); ids.bind(java.util.List.of(1, 2));");
        compile(false, "HanaInput.requiredTextList(\"P_NAMES\").bind(java.util.List.of(1,2));");
        compile(false, "HanaInput.requiredNumericList(\"P_IDS\", Integer.class).bind(java.util.List.of(1L));");
        compile(true, "var amount = HanaInput.optional(\"P_MIN_AMOUNT\", BigDecimal.class); var function = HanaTableFunction.of(\"REPORT\", \"FUNCTION\", HanaViewRowProxy::createTable, amount); BigDecimal value = function.query(null, amount.bind(BigDecimal.ONE)).singleNotNull().getAmount();");
        compile(false, "var function = HanaTableFunction.of(\"REPORT\", \"FUNCTION\", HanaViewRowProxy::createTable); String wrong = function.query(null).singleNotNull().getAmount();");
    }

    @Test
    void hanaHierarchyKeysRootsAndOutputRemainTyped() throws IOException {
        compile(true, "HanaHierarchy<Integer> hierarchy = HanaHierarchy.from(q, x -> HanaHierarchySpec.of(x.id(), x.id()).roots(java.util.List.of(1))); Long rank = hierarchy.query(org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaHierarchyRowProxy::createTable).singleNotNull().getRank();");
        compile(false, "HanaHierarchy.from(q, x -> HanaHierarchySpec.of(x.id(), x.name()));");
        compile(false, "HanaHierarchy.from(q, x -> HanaHierarchySpec.of(x.id(), x.id()).roots(java.util.List.of(\"bad\")));");
        compile(true, "HanaHierarchy<Integer> hierarchy = HanaHierarchy.from(q, x -> HanaHierarchySpec.of(x.id(), x.id())); Integer distance = hierarchy.ancestorsOf(java.util.List.of(1)).distanceBetween(-2, 0).query(org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaHierarchyNavigationRowProxy::createTable).singleNotNull().getDistance();");
        compile(false, "HanaHierarchy.from(q, x -> HanaHierarchySpec.of(x.id(), x.id())).descendantsOf(java.util.List.of(\"bad\"));");
    }

    @Test
    void hanaSearchRequiresTextColumnsAndKeepsTheScoreType() throws IOException {
        compile(true, "HanaSearch search = HanaSearch.from(q, x -> HanaSearchSpec.exact(\"coffee\", x.name())); Double score = search.query(org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy::createTable).singleNotNull().getScore();");
        compile(false, "HanaSearch.from(q, x -> HanaSearchSpec.exact(\"coffee\", x.id()));");
        compile(false, "HanaSearch.from(q, x -> HanaSearchSpec.exact(\"coffee\", x.name().toUpper()));");
        compile(false, "HanaSearch.from(q, x -> HanaSearchSpec.fuzzy(\"coffee\", \"0.7\", x.name()));");
        compile(false, "String score = HanaSearch.from(q, x -> HanaSearchSpec.exact(\"coffee\", x.name())).query(org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy::createTable).singleNotNull().getScore();");
    }

    @Test
    void mysqlSearchRequiresPhysicalTextAndKeepsTheScoreType() throws IOException {
        compile(true, "var search = MySqlSearch.from(q, x -> MySqlSearchSpec.booleanMode(\"+coffee -tea\", x.name())); Double score = search.query(org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy::createTable).singleNotNull().getScore();");
        compile(false, "MySqlSearch.from(q, x -> MySqlSearchSpec.naturalLanguage(\"coffee\", x.id()));");
        compile(false, "MySqlSearch.from(q, x -> MySqlSearchSpec.naturalLanguage(\"coffee\", x.name().toUpper()));");
        compile(false, "String score = MySqlSearch.from(q, x -> MySqlSearchSpec.naturalLanguage(\"coffee\", x.name())).query(org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy::createTable).singleNotNull().getScore();");
    }

    @Test
    void postgresSearchRequiresVectorColumnsAndTypedEncodedQueries() throws IOException {
        String source = "com.easy.query.api.proxy.entity.select.EntityQueryable<org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgSearchDocumentProxy, org.dpdns.zerodep.easyquery.pro.test.entity.PgSearchDocument> sq = null;";
        compile(true, source + "var search = PgSearch.from(sq, x -> PgSearchSpec.plain(\"pg_catalog.english\", \"coffee\", x.document()).rankedBy(PgSearchSpec.Ranking.COVER_DENSITY, PgSearchSpec.Normalization.DOCUMENT_LENGTH)); Double score = search.query(org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy::createTable).singleNotNull().getScore();");
        compile(true, source + "PgSearch.from(sq, x -> PgSearchSpec.query(new PgTsQuery(\"'coff':*\"), x.document()));");
        compile(false, source + "PgSearch.from(sq, x -> PgSearchSpec.web(\"english\", \"coffee\", x.title()));");
        compile(false, source + "PgSearch.from(sq, x -> PgSearchSpec.plain(\"english\", \"coffee\", x.condition()));");
        compile(false, source + "PgSearch.from(sq, x -> PgSearchSpec.query(\"coffee\", x.document()));");
        compile(false, source + "String score = PgSearch.from(sq, x -> PgSearchSpec.plain(\"english\", \"coffee\", x.document())).query(org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy::createTable).singleNotNull().getScore();");
    }

    @Test
    void postgresTextSearchValuesCannotExchangeTheirTypes() throws IOException {
        compile(true, "AnyTypeExpression<PgTsVector> vector = PgSqlDsl.of(p).value(new PgTsVector(\"'coffee':1\"), PgSqlTypes.tsvector()); AnyTypeExpression<PgTsQuery> query = PgSqlDsl.of(p).value(new PgTsQuery(\"'coffee'\"), PgSqlTypes.tsquery());");
        compile(true, "AnyTypeExpression<PgTsVector> vector = PgSqlDsl.of(p).nativeCast(p.name(), PgSqlTypes.tsvector());");
        compile(false, "PgSqlDsl.of(p).value(new PgTsQuery(\"'coffee'\"), PgSqlTypes.tsvector());");
        compile(false, "AnyTypeExpression<PgTsQuery> query = PgSqlDsl.of(p).nativeCast(p.name(), PgSqlTypes.tsvector());");
        compile(false, "PgSqlDsl.of(p).value(\"coffee\", PgSqlTypes.tsvector());");
    }

    @Test
    void postgresFixedTimeBinsRequireLocalTimestampsAndDurations() throws IOException {
        compile(true, "DateTimeTypeExpression<java.time.LocalDateTime> bin = PgSqlDsl.of(e).dateBin(e.occurred(), java.time.Duration.ofMinutes(15), java.time.LocalDateTime.of(1970,1,1,0,0));");
        compile(false, "PgSqlDsl.of(e).dateBin(e.eventDate(), java.time.Duration.ofDays(1), java.time.LocalDateTime.of(1970,1,1,0,0));");
        compile(false, "PgSqlDsl.of(e).dateBin(e.occurred(), java.time.Period.ofMonths(1), java.time.LocalDateTime.of(1970,1,1,0,0));");
        compile(false, "PgSqlDsl.of(e).dateBin(e.occurred(), java.time.Duration.ofMinutes(15), java.time.Instant.EPOCH);");
    }
}
