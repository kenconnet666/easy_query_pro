package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.api.database.CodeFirstCommandTxArg;
import org.dpdns.zerodep.easyquery.pro.test.entity.LobDocument;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.IOException;
import java.io.Reader;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.migration.data.TableMigrationData;
import java.util.Arrays;
import java.util.Collections;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import com.zaxxer.hikari.HikariDataSource;
import com.easy.query.core.basic.jdbc.executor.internal.enumerable.JdbcStreamResult;
import java.io.DataInputStream;

import static org.junit.jupiter.api.Assertions.*;

class HanaLobIT {
    @Test
    void materializedValuesPreserveLargeUnicodeBinaryUuidEmptyAndNullRows() throws SQLException {
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("LOB_VALUES")) {
            DefaultEasyEntityQuery query = HanaTestDatabase.client();
            EasyQueryClient client = query.getEasyQueryClient();
            TableMigrationData table = client.getRuntimeContext().getMigrationsSQLGenerator().parseEntity(LobDocument.class);
            table.setSchema(schema.name());
            client.getDatabaseCodeFirst().createTableCommandByMigrationData(Arrays.asList(table)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            LobDocument large = document(1, String.join("", Collections.nCopies(100_000, "中文😀")), String.join("", Collections.nCopies(30_000, "ASCII-")), binary(1_048_589));
            large.setExternalId(UUID.fromString("76038e38-0fc7-44bd-b6ef-842dc8d097a9"));
            LobDocument empty = document(2, "", "", new byte[0]);
            LobDocument absent = document(3, null, null, null);
            assertEquals(3L, query.insertable(Arrays.asList(large, empty, absent)).asSchema(s -> schema.name()).batch(true).executeRows());
            List<LobDocument> rows = query.queryable(LobDocument.class).asSchema(s -> schema.name()).orderBy(d -> d.id().asc()).toList();
            assertEquals(3, rows.size());
            assertEquals(large.getText(), rows.get(0).getText());
            assertEquals(large.getAscii(), rows.get(0).getAscii());
            assertArrayEquals(large.getData(), rows.get(0).getData());
            assertEquals(large.getExternalId(), rows.get(0).getExternalId());
            assertEquals("", rows.get(1).getText());
            assertEquals("", rows.get(1).getAscii());
            assertArrayEquals(new byte[0], rows.get(1).getData());
            assertNull(rows.get(2).getText());
            assertNull(rows.get(2).getAscii());
            assertNull(rows.get(2).getData());
            assertNull(rows.get(2).getExternalId());
            assertEquals(1L, query.queryable(LobDocument.class).asSchema(s -> schema.name()).where(d -> d.externalId().eq(large.getExternalId())).count());
            assertEquals(Arrays.asList(large.getExternalId()), query.queryable(LobDocument.class).asSchema(s -> schema.name())
                    .where(d -> d.id().eq(1)).selectColumn(d -> d.externalId()).toList());
            try (Transaction transaction = client.beginTransaction()) {
                LobDocument inserted = document(4, "rollback", "rollback", new byte[]{1, 2});
                assertThrows(RuntimeException.class, () -> query.insertable(Arrays.asList(inserted, large)).asSchema(s -> schema.name()).batch(true).executeRows());
                transaction.rollback();
            }
            assertEquals(3L, query.queryable(LobDocument.class).asSchema(s -> schema.name()).count());
        }
    }

    @Test
    void jdbcLobInputsAndStreamedResultsRetainCallerOwnershipAndReleaseThePool() throws SQLException, IOException {
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("LOB_STREAM", "\"id\" INTEGER PRIMARY KEY, \"text\" NCLOB, \"ascii\" CLOB, \"data\" BLOB");
             HikariDataSource pool = HanaTestDatabase.pool(1);
             LobDocument.Streams input = streams(1, String.join("", Collections.nCopies(70_000, "中😀")), String.join("", Collections.nCopies(20_000, "abcdef")), binary(1_048_589));
             LobDocument.Streams empty = streams(2, "", "", new byte[0])) {
            DefaultEasyEntityQuery query = HanaTestDatabase.client(pool);
            EasyQueryClient client = query.getEasyQueryClient();
            LobDocument.Streams absent = new LobDocument.Streams();
            absent.setId(3);
            assertEquals(3L, client.insertable(Arrays.asList(input, empty, absent)).asTable(t -> table.name()).batch(true).executeRows());
            assertEquals(1_048_589L, input.getData().length());
            assertEquals(210_000L, input.getText().length());
            try (Transaction transaction = client.beginTransaction()) {
                try (JdbcStreamResult<LobDocument.Streams> result = client.queryable(LobDocument.Streams.class).asTable(t -> table.name()).orderBy(d -> d.column("id"), true).toStreamResult(1)) {
                    int count = 0;
                    for (LobDocument.Streams streams : result.getStreamIterable()) {
                        try (LobDocument.Streams row = streams) {
                            if (row.getId() == 1) {
                                try (Reader text = row.getText().getCharacterStream(); Reader ascii = row.getAscii().getCharacterStream();
                                     InputStream data = row.getData().getBinaryStream()) {
                                    assertCharacters(String.join("", Collections.nCopies(70_000, "中😀")), text);
                                    assertCharacters(String.join("", Collections.nCopies(20_000, "abcdef")), ascii);
                                    CRC32 crc = new CRC32();
                                    byte[] buffer = new byte[8192];
                                    long length = 0;
                                    for (int read; (read = data.read(buffer)) != -1; ) {
                                        crc.update(buffer, 0, read);
                                        length += read;
                                    }
                                    assertEquals(1_048_589L, length);
                                    CRC32 expected = new CRC32();
                                    expected.update(binary(1_048_589));
                                    assertEquals(expected.getValue(), crc.getValue());
                                }
                            } else if (row.getId() == 2) {
                                assertEquals(0, row.getText().length());
                                assertEquals(0, row.getAscii().length());
                                assertEquals(0, row.getData().length());
                            } else {
                                assertNull(row.getText());
                                assertNull(row.getAscii());
                                assertNull(row.getData());
                            }
                            count++;
                        }
                    }
                    assertEquals(3, count);
                }
                transaction.commit();
            }
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            assertEquals(3L, client.queryable(LobDocument.Streams.class).asTable(t -> table.name()).count());
        }
    }

    @Test
    void earlyExitAndConsumerFailureCloseLobStreamsAndReturnConnections() throws SQLException {
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("LOB_EXIT", "\"id\" INTEGER PRIMARY KEY, \"text\" NCLOB, \"ascii\" CLOB, \"data\" BLOB");
             HikariDataSource pool = HanaTestDatabase.pool(1);
             LobDocument.Streams input = streams(1, String.join("", Collections.nCopies(30_000, "中😀")), "x", binary(524_301))) {
            EasyQueryClient client = HanaTestDatabase.client(pool).getEasyQueryClient();
            client.insertable(input).asTable(t -> table.name()).executeRows();
            for (boolean fail : Arrays.asList(false, true)) {
                try {
                    try (Transaction transaction = client.beginTransaction();
                         JdbcStreamResult<LobDocument.Streams> result = client.queryable(LobDocument.Streams.class).asTable(t -> table.name()).toStreamResult(1)) {
                        for (LobDocument.Streams streams : result.getStreamIterable()) {
                            try (LobDocument.Streams row = streams; InputStream data = row.getData().getBinaryStream()) {
                                new DataInputStream(data).readFully(new byte[4096]); // EOFException when fewer than 4096 bytes arrive
                                if (fail) throw new IllegalStateException("consumer failure");
                                break;
                            }
                        }
                    }
                    assertFalse(fail);
                } catch (IllegalStateException failure) {
                    assertTrue(fail);
                    assertEquals("consumer failure", failure.getMessage());
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
                assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
                assertEquals(1L, client.queryable(LobDocument.Streams.class).asTable(t -> table.name()).count());
            }
        }
    }

    @Test
    void bindingAndReadFailuresCloseOwnedStreamsWithoutFreeingCallerLobs() throws SQLException {
        try (HanaTestDatabase.TestTable table = HanaTestDatabase.createTable("LOB_FAILURE", "\"id\" INTEGER PRIMARY KEY, \"data\" BLOB, \"text\" NCLOB");
             HikariDataSource pool = HanaTestDatabase.pool(1)) {
            EasyQueryClient client = HanaTestDatabase.client(pool).getEasyQueryClient();
            HanaLobIT.TrackingBlob blob = new TrackingBlob(false);
            SerialClob broken = new SerialClob(new char[]{'x'}) {
                @Override
                public Reader getCharacterStream() throws javax.sql.rowset.serial.SerialException {
                    throw new javax.sql.rowset.serial.SerialException("cannot open reader");
                }
            };
            try {
                try (Transaction transaction = client.beginTransaction()) {
                    String active = "SELECT COUNT(*) FROM M_ACTIVE_STATEMENTS WHERE CONNECTION_ID=CURRENT_CONNECTION";
                    long before = client.sqlQuery(active, Long.class).get(0);
                    assertThrows(RuntimeException.class, () -> client.sqlExecute("INSERT INTO \"" + table.name() + "\" VALUES (?,?,?)", Arrays.asList(1, blob, broken)));
                    assertTrue(blob.opened);
                    assertTrue(blob.closed);
                    assertFalse(blob.freed);
                    assertEquals(before, client.sqlQuery(active, Long.class).get(0));
                    transaction.rollback();
                }
            } finally {
                blob.free();
                broken.free();
            }
            HanaLobIT.TrackingBlob failingRead = new TrackingBlob(true);
            try {
                assertThrows(RuntimeException.class, () -> client.sqlQuery("SELECT LENGTH(?) FROM DUMMY", Long.class, Arrays.asList(failingRead)));
                assertTrue(failingRead.opened);
                assertTrue(failingRead.closed);
                assertFalse(failingRead.freed);
            } finally {
                failingRead.free();
            }
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            assertEquals(1, client.sqlQuery("SELECT 1 FROM DUMMY", Integer.class).get(0));
        }
    }

    private static final class TrackingBlob extends SerialBlob {
        private final boolean failRead;
        private boolean opened;
        private boolean closed;
        private boolean freed;

        private TrackingBlob(boolean failRead) throws SQLException {
            super(binary(1_048_589));
            this.failRead = failRead;
        }

        @Override
        public byte[] getBytes(long position, int length) throws javax.sql.rowset.serial.SerialException {
            throw new javax.sql.rowset.serial.SerialException("LOB inputs must not be eagerly materialized");
        }

        @Override
        public InputStream getBinaryStream() throws javax.sql.rowset.serial.SerialException {
            opened = true;
            return new FilterInputStream(super.getBinaryStream()) {
                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (failRead) throw new IOException("input read failure");
                    return super.read(bytes, offset, length);
                }

                @Override
                public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            };
        }

        @Override
        public void free() throws SQLException {
            freed = true;
            super.free();
        }
    }

    private static LobDocument document(int id, String text, String ascii, byte[] data) {
        LobDocument result = new LobDocument();
        result.setId(id);
        result.setText(text);
        result.setAscii(ascii);
        result.setData(data);
        return result;
    }

    private static LobDocument.Streams streams(int id, String text, String ascii, byte[] data) throws SQLException {
        LobDocument.Streams result = new LobDocument.Streams();
        result.setId(id);
        result.setText(new SerialClob(text.toCharArray()));
        result.setAscii(new SerialClob(ascii.toCharArray()));
        result.setData(new SerialBlob(data));
        return result;
    }

    private static byte[] binary(int size) {
        byte[] result = new byte[size];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (i * 31);
        return result;
    }

    private static void assertCharacters(String expected, Reader reader) throws IOException {
        char[] buffer = new char[4096];
        int offset = 0;
        for (int read; (read = reader.read(buffer)) != -1; ) {
            assertEquals(expected.substring(offset, offset + read), new String(buffer, 0, read));
            offset += read;
        }
        assertEquals(expected.length(), offset);
    }
}
