package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.conn.EasyConnection;
import com.easy.query.core.basic.jdbc.executor.ExecutorContext;
import com.easy.query.core.basic.jdbc.executor.internal.props.BasicJdbcProperty;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.sql.DefaultJdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.hana.key.HanaSequenceGenerator;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Adapts generated-key retrieval while retaining upstream binding, transactions and listeners. */
public final class HanaJdbcSQLExecutor extends DefaultJdbcSQLExecutor {
    @Override
    public <T> int insert(ExecutorContext context, EasyConnection connection, String sql, List<T> entities,
                          List<SQLParameter> parameters, boolean fillKeys, boolean sharding, boolean replica)
            throws SQLException {
        if (!fillKeys || entities.isEmpty()) {
            return super.insert(context, connection, sql, entities, parameters, fillKeys, sharding, replica);
        }
        var metadata = context.getRuntimeContext().getEntityMetadataManager().getEntityMetadata(entities.get(0).getClass());
        List<ColumnMetadata> columns = metadata.getGeneratedKeyColumns().stream()
                .map(name -> metadata.getColumnNotNull(metadata.getPropertyNameNotNull(name))).toList();
        String keyQuery = generatedKeyQuery(columns);
        int total = 0;
        // ngdbc exposes neither JDBC generated-key overload. Session values must be read
        // immediately for each inserted row; a batched insert would retain only the last value.
        for (T entity : entities) {
            int affected = super.insert(context, connection, sql, List.of(entity), parameters, false, sharding, replica);
            if (affected != 1) {
                throw new SQLException("HANA generated-key insert expected one affected row, received " + affected);
            }
            try (var result = super.query(context, connection, keyQuery, List.of(), sharding, replica)) {
                if (!result.next()) throw new SQLException("HANA did not return generated keys");
                for (int i = 0; i < columns.size(); i++) {
                    var column = columns.get(i);
                    Object key = column.getJdbcTypeHandler().getValue(new BasicJdbcProperty(i, column.getPropertyType()), result);
                    if (key == null) throw new SQLException("HANA generated key is null for " + column.getPropertyName());
                    column.getSetterCaller().call(entity, key);
                }
            }
            total = Math.addExact(total, affected);
        }
        return total;
    }

    private String generatedKeyQuery(List<ColumnMetadata> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("HANA key backfill requires @Column(generatedKey = true)");
        }
        var expressions = new ArrayList<String>(columns.size());
        var sources = new HashSet<String>();
        for (var column : columns) {
            var generator = column.getGeneratedSQLColumnGenerator();
            String expression;
            if (generator == null) {
                expression = "CURRENT_IDENTITY_VALUE()";
            } else if (generator instanceof HanaSequenceGenerator sequence) {
                expression = sequence.currentValueExpression();
            } else {
                throw new UnsupportedOperationException("HANA cannot backfill custom generator "
                        + generator.getClass().getName() + " for " + column.getPropertyName()
                        + "; use HanaSequenceGenerator or supply the key before inserting");
            }
            if (!sources.add(expression)) {
                throw new IllegalArgumentException("Multiple generated columns cannot share a HANA session key source: " + expression);
            }
            expressions.add(expression);
        }
        return "SELECT " + String.join(",", expressions) + " FROM DUMMY";
    }
}
