package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.BlobTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.CLobTypeHandler;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 流必须活到 execute/executeBatch 结束；不复制整个 LOB，也不释放调用方的 Blob/Clob。
 */
final class HanaLobBindings {
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    Scope open() {
        var scope = new Scope(current.get());
        current.set(scope);
        return scope;
    }

    private Scope binding(PreparedStatement statement) throws SQLException {
        var scope = current.get();
        if (scope == null) throw new SQLException("Streaming HANA LOB parameters require HanaJdbcSQLExecutor", "HY000");
        scope.statements.add(statement);
        return scope;
    }

    final class Binary extends BlobTypeHandler {
        @Override
        public void setParameter(EasyParameter parameter) throws SQLException {
            var scope = binding(parameter.getPs());
            var blob = (Blob) parameter.getValue();
            long length = blob.length();
            var stream = blob.getBinaryStream();
            scope.streams.add(stream);
            parameter.getPs().setBinaryStream(parameter.getIndex(), stream, length);
        }
    }

    final class Characters extends CLobTypeHandler {
        @Override
        public void setParameter(EasyParameter parameter) throws SQLException {
            var scope = binding(parameter.getPs());
            var clob = (Clob) parameter.getValue();
            long length = clob.length();
            var reader = clob.getCharacterStream();
            scope.streams.add(reader);
            parameter.getPs().setCharacterStream(parameter.getIndex(), reader, length);
        }
    }

    final class Scope implements AutoCloseable {
        private final Scope previous;
        private final ArrayList<Closeable> streams = new ArrayList<>();
        private final Set<PreparedStatement> statements = Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean completed;

        private Scope(Scope previous) {
            this.previous = previous;
        }

        void completed() {
            completed = true;
        }

        @Override
        public void close() throws SQLException {
            if (previous == null) current.remove();
            else current.set(previous);
            SQLException failure = null;
            // 上游 createPreparedStatement 在参数绑定失败时还没有交出 statement；此时由作用域补齐清理。
            if (!completed) {
                for (var statement : statements) {
                    try {
                        statement.close();
                    } catch (SQLException error) {
                        if (failure == null) failure = error;
                        else failure.addSuppressed(error);
                    }
                }
            }
            for (int i = streams.size() - 1; i >= 0; i--) {
                try {
                    streams.get(i).close();
                } catch (IOException | RuntimeException error) {
                    if (failure == null) failure = new SQLException("Unable to close HANA LOB input stream", error);
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
