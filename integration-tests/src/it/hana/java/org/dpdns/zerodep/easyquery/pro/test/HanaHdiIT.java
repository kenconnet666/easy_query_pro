package org.dpdns.zerodep.easyquery.pro.test;

import org.junit.jupiter.api.Test;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaCalculationView;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaInput;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaTableFunction;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaViewRowProxy;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.io.ByteArrayOutputStream;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.io.InputStream;
import java.sql.ResultSet;
import com.easy.query.core.api.client.EasyQueryClient;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaViewRow;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import com.easy.query.core.api.pagination.EasyPageResult;
import java.sql.CallableStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HanaHdiIT {
    @Test
    void deploysAndQueriesRealParameterizedSources() throws SQLException, IOException {
        // HDI 容器名称比普通带引号的 schema 更严格，只接受大写字母、数字和下划线。
        String container = HanaTestDatabase.uniqueName("HDI").toUpperCase(Locale.ROOT);
        try (Connection connection = HanaTestDatabase.connect(); Statement setup = connection.createStatement()) {
            setup.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_PRIVILEGES LIKE _SYS_DI.TT_API_PRIVILEGES");
            setup.execute("INSERT INTO #EQP_HDI_PRIVILEGES SELECT PRIVILEGE_NAME, OBJECT_NAME, PRINCIPAL_SCHEMA_NAME,"
                    + " CURRENT_USER FROM _SYS_DI.T_DEFAULT_CONTAINER_ADMIN_PRIVILEGES");
            setup.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_DROP_PARAMETERS LIKE _SYS_DI.TT_PARAMETERS");
            setup.execute("INSERT INTO #EQP_HDI_DROP_PARAMETERS SELECT 'ignore_work','true' FROM DUMMY"
                    + " UNION ALL SELECT 'ignore_deployed','true' FROM DUMMY");
            invoke(connection, "CALL _SYS_DI.CREATE_CONTAINER('" + container
                    + "', _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
            try {
                invoke(connection, "CALL _SYS_DI.GRANT_CONTAINER_API_PRIVILEGES('" + container
                        + "', #EQP_HDI_PRIVILEGES, _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
                invoke(connection, "CALL \"" + container + "#DI\".CONFIGURE_LIBRARIES(_SYS_DI.T_DEFAULT_LIBRARIES,"
                        + " _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
                deploy(connection, container);
                grantSchemaAccess(connection, container);
                verifyCalculationView(connection, container);
                verifyQueryableSource(container);
                verifyMultiValueSources(connection, container);
                verifyTableFunctionSource(container);
                verifyRuntimePrivileges(connection, container);
            } finally {
                invoke(connection, "CALL _SYS_DI.DROP_CONTAINER('" + container
                        + "', #EQP_HDI_DROP_PARAMETERS, ?, ?, ?)");
            }
        }
    }

    private static void deploy(Connection connection, String container) throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_FILES LIKE _SYS_DI.TT_FILESFOLDERS_CONTENT");
            statement.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_PATHS LIKE _SYS_DI.TT_FILESFOLDERS");
        }
        try (PreparedStatement files = connection.prepareStatement("INSERT INTO #EQP_HDI_FILES (PATH,CONTENT) VALUES (?,?)");
             PreparedStatement paths = connection.prepareStatement("INSERT INTO #EQP_HDI_PATHS (PATH) VALUES (?)")) {
            for (String path : Arrays.asList(".hdiconfig", "EQP_SOURCE.hdbtable", "EQP_CALC.hdbcalculationview",
                    "EQP_CALC_NAMES.hdbcalculationview", "EQP_CALC_IDS.hdbcalculationview", "EQP_FUNCTION.hdbfunction")) {
                try (InputStream resource = HanaHdiIT.class.getResourceAsStream("/hana/hdi/" + path)) {
                    assertNotNull(resource, path);
                    files.setString(1, path);
                    files.setBytes(2, readAll(resource));
                    files.executeUpdate();
                }
                paths.setString(1, path);
                paths.executeUpdate();
            }
        }
        invoke(connection, "CALL \"" + container + "#DI\".WRITE(#EQP_HDI_FILES, _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
        invoke(connection, "CALL \"" + container + "#DI\".MAKE(#EQP_HDI_PATHS, _SYS_DI.T_NO_FILESFOLDERS,"
                + " _SYS_DI.T_NO_FILESFOLDERS_PARAMETERS, _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
    }

    private static void grantSchemaAccess(Connection connection, String container) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_SCHEMA_PRIVILEGES LIKE _SYS_DI.TT_SCHEMA_PRIVILEGES");
            statement.execute("INSERT INTO #EQP_HDI_SCHEMA_PRIVILEGES SELECT 'SELECT','',CURRENT_USER FROM DUMMY");
            statement.execute("INSERT INTO #EQP_HDI_SCHEMA_PRIVILEGES SELECT 'INSERT','',CURRENT_USER FROM DUMMY");
            statement.execute("INSERT INTO #EQP_HDI_SCHEMA_PRIVILEGES SELECT 'EXECUTE','',CURRENT_USER FROM DUMMY");
        }
        invoke(connection, "CALL _SYS_DI.GRANT_CONTAINER_SCHEMA_PRIVILEGES('" + container
                + "', #EQP_HDI_SCHEMA_PRIVILEGES, _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
    }

    private static void verifyCalculationView(Connection connection, String container) throws SQLException {
        String schema = '"' + container + '"';
        try (PreparedStatement metadata = connection.prepareStatement("SELECT VIEW_TYPE FROM SYS.VIEWS WHERE SCHEMA_NAME=? AND VIEW_NAME='EQP_CALC'")) {
            metadata.setString(1, container);
            try (ResultSet result = metadata.executeQuery()) {
                assertTrue(result.next());
                assertEquals("CALC", result.getString(1));
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + schema + ".\"EQP_SOURCE\" VALUES (?,?,?)")) {
            for (int id = 1; id <= 2; id++) {
                insert.setInt(1, id);
                insert.setString(2, id == 1 ? "O'Reilly 中文" : "other");
                insert.setBigDecimal(3, new BigDecimal("123.4500"));
                insert.executeUpdate();
            }
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT \"ID\",\"NAME\",\"AMOUNT\" FROM " + schema
                + ".\"EQP_CALC\" (PLACEHOLDER.\"$$P_NAME$$\" => ?)")) {
            select.setString(1, "O'Reilly 中文");
            try (ResultSet result = select.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertEquals("O'Reilly 中文", result.getString(2));
                assertEquals(new BigDecimal("123.4500"), result.getBigDecimal(3));
                assertFalse(result.next());
            }
            select.setString(1, "' OR 1=1 --");
            try (ResultSet result = select.executeQuery()) {
                assertFalse(result.next());
            }
        }
    }

    private static void verifyQueryableSource(String schema) throws SQLException {
        EasyQueryClient client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure).getEasyQueryClient();
        HanaInput<String> name = HanaInput.required("P_NAME", String.class);
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> view = HanaCalculationView.of(schema, "EQP_CALC", HanaViewRowProxy::createTable, name);
        EntityQueryable<HanaViewRowProxy, HanaViewRow> matching = view.query(client, name.bind("O'Reilly 中文"));
        HanaViewRow row = matching.cloneQueryable().singleNotNull();
        assertEquals(1, row.getId());
        assertEquals("O'Reilly 中文", row.getName());
        assertEquals(new BigDecimal("123.4500"), row.getAmount());
        assertEquals(1L, matching.cloneQueryable().count());
        assertEquals(1L, matching.cloneQueryable().orderBy(r -> r.id().asc()).toPageResult(1, 1).getTotal());
        assertTrue(view.query(client, name.bind("' OR 1=1 --")).toList().isEmpty());
        HanaInput<String> nullableName = HanaInput.required("P_NAME", String.class).nullable();
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> nullableView = HanaCalculationView.of(schema, "EQP_CALC", HanaViewRowProxy::createTable, nullableName);
        assertTrue(nullableView.query(client, nullableName.bind(null)).toList().isEmpty());
        HanaInput<String> defaultName = HanaInput.optional("P_NAME", String.class).nullable();
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> defaultView = HanaCalculationView.of(schema, "EQP_CALC", HanaViewRowProxy::createTable, defaultName);
        assertEquals(2, defaultView.query(client).singleNotNull().getId());
        assertTrue(defaultView.query(client, defaultName.bind(null)).toList().isEmpty());
        EntityQueryable<Draft2Proxy<String, BigDecimal>, Draft2<String, BigDecimal>> filtered = matching.cloneQueryable().where(r -> r.id().gt(0)).select(r -> Select.DRAFT.of(r.name(), r.amount()));
        ToSQLResult sql = filtered.cloneQueryable().toSQLResult();
        assertEquals(Arrays.asList("O'Reilly 中文", 0), sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals("O'Reilly 中文", filtered.cloneQueryable().toCteAs("calculation_rows").singleNotNull().getValue1());
        EntityQueryable<Draft2Proxy<String, BigDecimal>, Draft2<String, BigDecimal>> grouped = matching.cloneQueryable().groupBy(r -> GroupKeys.of(r.name()))
                .select(g -> Select.DRAFT.of(g.key1(), g.groupTable().amount().sumBigDecimal()));
        assertEquals(0, new BigDecimal("123.4500").compareTo(grouped.singleNotNull().getValue2()));
        // 两次 query 分别生成代理，验证来源定义可以复用为同一 SQL 中的独立 JOIN 别名。
        EntityQueryable<Draft2Proxy<Integer, String>, Draft2<Integer, String>> joined = view.query(client, name.bind("O'Reilly 中文"))
                .leftJoin(view.query(client, name.bind("other")), (left, right) -> left.id().eq(right.id()))
                .select((left, right) -> Select.DRAFT.of(left.id(), right.name()));
        Draft2<Integer, String> joinedRow = joined.singleNotNull();
        assertEquals(1, joinedRow.getValue1());
        org.junit.jupiter.api.Assertions.assertNull(joinedRow.getValue2());
        try (Transaction transaction = client.beginTransaction()) {
            assertEquals(1L, client.sqlExecute("INSERT INTO \"" + schema + "\".\"EQP_SOURCE\" VALUES (?,?,?)",
                    Arrays.asList(3, "transaction value", new BigDecimal("7.0000"))));
            assertEquals(3, view.query(client, name.bind("transaction value")).singleNotNull().getId());
            transaction.rollback();
        }
        assertTrue(view.query(client, name.bind("transaction value")).toList().isEmpty());
    }

    private static void verifyMultiValueSources(Connection connection, String schema) throws SQLException {
        List<String> names = Arrays.asList("O'Reilly, 中😀", "A\"B", "", "x') OR 1=1 --");
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO \"" + schema + "\".\"EQP_SOURCE\" VALUES (?,?,?)")) {
            for (int index = 0; index < names.size(); index++) {
                insert.setInt(1, index + 3);
                insert.setString(2, names.get(index));
                insert.setBigDecimal(3, BigDecimal.valueOf(index + 3));
                insert.executeUpdate();
            }
        }
        EasyQueryClient client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure).getEasyQueryClient();
        HanaInput<List<String>> nameList = HanaInput.requiredTextList("P_NAMES");
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> textView = HanaCalculationView.of(schema, "EQP_CALC_NAMES", HanaViewRowProxy::createTable, nameList);
        EntityQueryable<HanaViewRowProxy, HanaViewRow> selected = textView.query(client, nameList.bind(Arrays.asList("O'Reilly 中文", names.get(0), names.get(1))));
        assertEquals(Arrays.asList(1, 3, 4), selected.cloneQueryable().orderBy(r -> r.id().asc()).toList().stream().map(r -> r.getId()).collect(Collectors.toList()));
        List<SQLParameter> parameters = selected.cloneQueryable().toSQLResult().getSqlContext().getParameters();
        assertEquals(1, parameters.size());
        assertEquals("'O''Reilly 中文','O''Reilly, 中😀','A\"B'", parameters.get(0).getValue());
        assertEquals(3L, selected.cloneQueryable().count());
        assertEquals(3L, selected.cloneQueryable().toCteAs("multi_name_rows").toPageResult(1, 2).getTotal());
        assertEquals(5, textView.query(client, nameList.bind(Arrays.asList(""))).singleNotNull().getId());
        assertEquals(6, textView.query(client, nameList.bind(Arrays.asList(names.get(3)))).singleNotNull().getId());
        assertTrue(textView.query(client, nameList.bind(Arrays.asList("not-there') OR 1=1 --"))).toList().isEmpty());
        assertEquals(1L, textView.query(client, nameList.bind(Arrays.asList("other", "other"))).count());

        HanaInput<List<Integer>> ids = HanaInput.requiredNumericList("P_IDS", Integer.class);
        HanaCalculationView<HanaViewRowProxy, HanaViewRow> numericView = HanaCalculationView.of(schema, "EQP_CALC_IDS", HanaViewRowProxy::createTable, ids);
        EntityQueryable<HanaViewRowProxy, HanaViewRow> numeric = numericView.query(client, ids.bind(Arrays.asList(4, 1))).orderBy(r -> r.id().asc());
        assertEquals(Arrays.asList(1, 4), numeric.cloneQueryable().toList().stream().map(r -> r.getId()).collect(Collectors.toList()));
        assertEquals("4,1", numeric.cloneQueryable().toSQLResult().getSqlContext().getParameters().get(0).getValue());
        assertTrue(numericView.query(client, ids.bind(Arrays.asList(-1))).toList().isEmpty());
    }

    private static void verifyTableFunctionSource(String schema) throws SQLException {
        EasyQueryClient client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure).getEasyQueryClient();
        HanaInput<String> name = HanaInput.optional("P_NAME", String.class).nullable();
        HanaInput<Integer> minId = HanaInput.optional("P_MIN_ID", Integer.class);
        HanaInput<BigDecimal> minAmount = HanaInput.optional("P_MIN_AMOUNT", BigDecimal.class);
        HanaTableFunction<HanaViewRowProxy, HanaViewRow> function = HanaTableFunction.of(schema, "EQP_FUNCTION", HanaViewRowProxy::createTable, name, minId, minAmount);

        assertEquals(2, function.query(client).singleNotNull().getId());
        assertEquals(6L, function.query(client, name.bind(null)).count());
        // 跳过中间的 P_MIN_ID，仅按名称绑定末项；实库证明 => ? 和默认值可以一起使用。
        EntityQueryable<HanaViewRowProxy, HanaViewRow> matching = function.query(client, minAmount.bind(new BigDecimal("100.0000")), name.bind("O'Reilly 中文"));
        HanaViewRow row = matching.cloneQueryable().singleNotNull();
        assertEquals(1, row.getId());
        assertEquals("O'Reilly 中文", row.getName());
        assertEquals(new BigDecimal("123.4500"), row.getAmount());
        EntityQueryable<HanaViewRowProxy, HanaViewRow> filtered = matching.cloneQueryable().where(r -> r.id().gt(0));
        assertEquals(Arrays.asList("O'Reilly 中文", new BigDecimal("100.0000"), 0),
                filtered.cloneQueryable().toSQLResult().getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(1L, filtered.cloneQueryable().toCteAs("function_rows").orderBy(r -> r.id().asc()).toPageResult(1, 2).getTotal());
        Draft2<String, BigDecimal> total = matching.cloneQueryable().groupBy(r -> GroupKeys.of(r.name()))
                .select(g -> Select.DRAFT.of(g.key1(), g.groupTable().amount().sumBigDecimal())).singleNotNull();
        assertEquals(0, new BigDecimal("123.4500").compareTo(total.getValue2()));
        assertTrue(function.query(client, name.bind("not-there') OR 1=1 --")).toList().isEmpty());
        assertEquals(6, function.query(client, name.bind("x') OR 1=1 --")).singleNotNull().getId());
        assertEquals(5, function.query(client, name.bind("")).singleNotNull().getId());
        assertEquals(3L, function.query(client, name.bind(null), minId.bind(4)).count());

        Draft2<Integer, String> joined = function.query(client, name.bind("O'Reilly 中文"))
                .leftJoin(function.query(client), (left, right) -> left.id().eq(right.id()))
                .select((left, right) -> Select.DRAFT.of(left.id(), right.name())).singleNotNull();
        assertEquals(1, joined.getValue1());
        org.junit.jupiter.api.Assertions.assertNull(joined.getValue2());
        // 表函数继续使用原 client 的事务，不能在来源包装中提前执行或换一条连接。
        try (Transaction transaction = client.beginTransaction()) {
            assertEquals(1L, client.sqlExecute("INSERT INTO \"" + schema + "\".\"EQP_SOURCE\" VALUES (?,?,?)",
                    Arrays.asList(99, "function transaction", new BigDecimal("9.0000"))));
            assertEquals(99, function.query(client, name.bind("function transaction")).singleNotNull().getId());
            transaction.rollback();
        }
        assertTrue(function.query(client, name.bind("function transaction")).toList().isEmpty());
    }

    private static void verifyRuntimePrivileges(Connection owner, String container) throws SQLException {
        try (HanaTestDatabase.TestUser user = HanaTestDatabase.createUser("HDI_READER")) {
            try (Statement statement = owner.createStatement()) {
                statement.execute("CREATE LOCAL TEMPORARY COLUMN TABLE #EQP_HDI_RUNTIME_PRIVILEGES LIKE _SYS_DI.TT_SCHEMA_PRIVILEGES");
            }
            try (PreparedStatement privileges = owner.prepareStatement("INSERT INTO #EQP_HDI_RUNTIME_PRIVILEGES VALUES (?,'',?)")) {
                for (String privilege : Arrays.asList("SELECT", "EXECUTE")) {
                    privileges.setString(1, privilege);
                    privileges.setString(2, user.name());
                    privileges.executeUpdate();
                }
            }
            invoke(owner, "CALL _SYS_DI.GRANT_CONTAINER_SCHEMA_PRIVILEGES('" + container
                    + "', #EQP_HDI_RUNTIME_PRIVILEGES, _SYS_DI.T_NO_PARAMETERS, ?, ?, ?)");
            // SQL SECURITY INVOKER 的表函数还需要底表 SELECT；EXECUTE 本身不是读取数据的授权。
            EasyQueryClient client = TestClients.configured(user.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure).getEasyQueryClient();
            assertEquals(user.name(), client.sqlQuery("SELECT CURRENT_USER FROM DUMMY", String.class).get(0));
            HanaInput<String> name = HanaInput.required("P_NAME", String.class);
            HanaCalculationView<HanaViewRowProxy, HanaViewRow> view = HanaCalculationView.of(container, "EQP_CALC", HanaViewRowProxy::createTable, name);
            assertEquals(1, view.query(client, name.bind("O'Reilly 中文")).singleNotNull().getId());
            HanaInput<List<String>> names = HanaInput.requiredTextList("P_NAMES");
            HanaCalculationView<HanaViewRowProxy, HanaViewRow> multiple = HanaCalculationView.of(container, "EQP_CALC_NAMES", HanaViewRowProxy::createTable, names);
            assertEquals(Arrays.asList(2, 5), multiple.query(client, names.bind(Arrays.asList("other", ""))).orderBy(p -> p.id().asc())
                    .toList().stream().map(row -> row.getId()).collect(Collectors.toList()));
            HanaInput<String> optionalName = HanaInput.optional("P_NAME", String.class).nullable();
            HanaTableFunction<HanaViewRowProxy, HanaViewRow> function = HanaTableFunction.of(container, "EQP_FUNCTION", HanaViewRowProxy::createTable, optionalName);
            assertEquals(2, function.query(client).singleNotNull().getId());
            assertEquals(6L, function.query(client, optionalName.bind(null)).count());
            EasyPageResult<HanaViewRow> page = function.query(client, optionalName.bind(null)).toCteAs("runtime_function_rows")
                    .where(p -> p.id().gt(1)).orderBy(p -> p.id().asc()).toPageResult(2, 2);
            assertEquals(5L, page.getTotal());
            assertEquals(Arrays.asList(4, 5), page.getData().stream().map(row -> row.getId()).collect(Collectors.toList()));
            assertEquals(258, HanaTestDatabase.databaseCause(assertThrows(RuntimeException.class,
                    () -> client.sqlExecute("UPDATE \"" + container + "\".\"EQP_SOURCE\" SET \"NAME\"=\"NAME\" WHERE \"ID\"=-1"))).getErrorCode());
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) != -1; ) bytes.write(buffer, 0, read);
        return bytes.toByteArray();
    }

    private static void invoke(Connection connection, String call) throws SQLException {
        // HDI 的 SQL 调用成功不代表部署成功，必须检查过程返回码和诊断消息。
        ArrayList<String> diagnostics = new ArrayList<String>();
        // 用顶层 CALL 传入临时表；包装成 SQLScript DO 会限制临时表参数的使用。
        try (CallableStatement statement = connection.prepareCall(call)) {
            statement.setQueryTimeout(60);
            statement.registerOutParameter(1, Types.INTEGER);
            statement.registerOutParameter(2, Types.BIGINT);
            boolean hasResult = statement.execute();
            while (hasResult || statement.getUpdateCount() != -1) {
                if (hasResult) {
                    try (ResultSet result = statement.getResultSet()) {
                        while (result.next()) {
                            diagnostics.add(result.getString("SEVERITY") + " " + result.getString("MESSAGE_CODE")
                                    + ": " + result.getString("MESSAGE") + " (" + result.getString("PATH") + ")");
                        }
                    }
                }
                hasResult = statement.getMoreResults();
            }
            assertEquals(0, statement.getInt(1), () -> "HDI failed: " + String.join("; ", diagnostics));
        }
    }
}
