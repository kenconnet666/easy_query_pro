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
        compile(true, "PgJsonbExpression<org.dpdns.zerodep.easyquery.pro.test.entity.PgJsonRecord.Payload> jsonb = PgSqlDsl.of(j).view(j.payload(), PgSqlTypes.jsonb()); j.payload().set(jsonb.setPath(java.util.List.of(\"name\"), JsonInput.encoded(\"\\\"new\\\"\"), true));");
        compile(false, "PgSqlDsl.of(p).value(JsonInput.encoded(\"{}\"), PgSqlTypes.json()).containsJson(JsonInput.jsonNull());");
        compile(false, "PgSqlDsl.of(p).value(\"{}\", PgSqlTypes.jsonb());");
        compile(false, "PgJsonbExpression<String> jsonb = PgSqlDsl.of(p).value(JsonInput.encoded(\"{}\"), PgSqlTypes.jsonb()).asText();");
    }

    private void compile(boolean expected, String body) throws IOException {
        var source = directory.resolve("Consumer.java");
        Files.writeString(source, """
                import java.math.BigDecimal;
                import com.easy.query.core.proxy.extension.functions.type.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
                import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;
                import org.dpdns.zerodep.easyquery.pro.dsl.json.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.*;
                import org.dpdns.zerodep.easyquery.pro.dsl.type.*;
                import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
                import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestEventProxy;
                import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgJsonRecordProxy;
                final class Consumer { void use(ProDsl d, TestProductProxy p, TestEventProxy e, PgJsonRecordProxy j) { %s } }
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
}
