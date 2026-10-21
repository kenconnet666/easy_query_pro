package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.basic.jdbc.executor.internal.props.BasicJdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import com.easy.query.core.enums.EntityMetadataTypeEnum;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTextSearchTypeHandlers;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsVector;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PGobject;

import java.lang.reflect.Proxy;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;

import static org.junit.jupiter.api.Assertions.*;

class PgTextSearchTypeTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void textSearchTypesBindTypedValuesAndRejectOtherDialects(DecimalDslTest.Backend backend) {
        PgTsVector vector = new PgTsVector("'coffee':1A");
        PgTsQuery query = new PgTsQuery("'coffee'");
        if (backend.dialect() != SqlDialect.POSTGRESQL) {
            assertThrows(IllegalArgumentException.class, () -> PgSqlTypes.tsvector().ddlType(backend.dialect()));
            assertThrows(IllegalArgumentException.class, () -> PgSqlTypes.tsquery().castTemplate(backend.dialect()));
            return;
        }
        EntityQueryable<TestProductProxy, TestProduct> source = backend.client().queryable(TestProduct.class).where(p -> p.id().eq(7));
        EntityQueryable<Draft2Proxy<PgTsVector, PgTsQuery>, Draft2<PgTsVector, PgTsQuery>> selected = source.select(p -> Select.DRAFT.of(PgSqlDsl.of(p).value(vector, PgSqlTypes.tsvector()),
                PgSqlDsl.of(p).value(query, PgSqlTypes.tsquery())));
        ToSQLResult result = selected.toSQLResult();
        assertTrue(result.getSQL().contains("CAST(? AS tsvector)"), result.getSQL());
        assertTrue(result.getSQL().contains("CAST(? AS tsquery)"), result.getSQL());
        assertEquals(Arrays.asList(vector, query, 7), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(result.getSQL(), selected.cloneQueryable().toSQL());
        ToSQLResult nulls = backend.client().queryable(TestProduct.class).selectColumn(p -> PgSqlDsl.of(p).value(null, PgSqlTypes.tsvector())).toSQLResult();
        assertEquals(Arrays.asList((Object) null), nulls.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
    }

    @Test
    void encodedValuesKeepEmptyDistinctFromNullAndLeaveGrammarToPostgres() {
        assertEquals("", new PgTsVector("").encoded());
        assertEquals("", new PgTsQuery("").encoded());
        assertDoesNotThrow(() -> new PgTsQuery("'coffee' &"));
        assertThrows(NullPointerException.class, () -> new PgTsVector(null));
        assertThrows(NullPointerException.class, () -> new PgTsQuery(null));
        for (String invalid : Arrays.asList("\0", "\uD800", "\uDC00")) {
            assertThrows(IllegalArgumentException.class, () -> new PgTsVector(invalid));
            assertThrows(IllegalArgumentException.class, () -> new PgTsQuery(invalid));
        }
    }

    @Test
    void scalarMetadataAndExplicitHandlerClassesRemainDistinct() {
        DefaultEasyEntityQuery query = TestClients.offline(new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        QueryRuntimeContext runtime = query.getRuntimeContext();
        PgSqlExtensions.configureTypeHandlers(runtime.getJdbcTypeHandlerManager());
        assertEquals(EntityMetadataTypeEnum.BASIC_TYPE, runtime.getEntityMetadataManager().getEntityMetadata(PgTsVector.class).getEntityMetadataType());
        assertEquals(EntityMetadataTypeEnum.BASIC_TYPE, runtime.getEntityMetadataManager().getEntityMetadata(PgTsQuery.class).getEntityMetadataType());
        JdbcTypeHandlerManager handlers = runtime.getJdbcTypeHandlerManager();
        assertSame(handlers.getHandler(PgTsVector.class), handlers.getHandlerByHandlerClass(PgTextSearchTypeHandlers.Vector.class));
        assertSame(handlers.getHandler(PgTsQuery.class), handlers.getHandlerByHandlerClass(PgTextSearchTypeHandlers.Query.class));
        assertNotEquals(handlers.getHandler(PgTsVector.class).getClass(), handlers.getHandler(PgTsQuery.class).getClass());
    }

    @Test
    void cachedDriverObjectsDoNotRequireAnUnsafeStringCast() throws SQLException {
        for (boolean vector : Arrays.asList(false, true)) {
            String type = vector ? "tsvector" : "tsquery";
            Class<?> carrier = vector ? PgTsVector.class : PgTsQuery.class;
            JdbcTypeHandler handler = vector ? new PgTextSearchTypeHandlers.Vector() : new PgTextSearchTypeHandlers.Query();
            BasicJdbcProperty property = new BasicJdbcProperty(0, carrier);
            for (String text : Arrays.asList("'coffee'", "")) {
                Object expected = vector ? new PgTsVector(text) : new PgTsQuery(text);
                PGobject nativeValue = new PGobject();
                nativeValue.setType(type);
                nativeValue.setValue(text);
                assertEquals(expected, handler.getValue(property, cached(type, nativeValue)));
                assertEquals(expected, handler.getValue(property, cached(type, text)));
            }
            assertNull(handler.getValue(property, cached(type, null)));
            PGobject unreadable = new PGobject();
            unreadable.setType(type);
            assertThrows(SQLException.class, () -> handler.getValue(property, cached(type, unreadable)));
            PGobject foreign = new PGobject();
            foreign.setType(vector ? "tsquery" : "tsvector");
            foreign.setValue("'coffee'");
            assertThrows(SQLException.class, () -> handler.getValue(property, cached(type, foreign)));
        }
    }

    private static StreamResultSet cached(String type, Object value) {
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getColumnTypeName")) return type;
                    throw new AssertionError(method.getName());
                });
        return (StreamResultSet) Proxy.newProxyInstance(StreamResultSet.class.getClassLoader(), new Class<?>[]{StreamResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getMetaData": return metadata;
                        case "getObject": return value;
                        case "wasNull": return value == null;
                        // 上游内存结果集对缓存值直接强转 String；不能在 handler 中依赖这条不适用于 PGobject 的路径。
                        case "getString": return (String) value;
                        default: throw new AssertionError(method.getName());
                    }
                });
    }
}
