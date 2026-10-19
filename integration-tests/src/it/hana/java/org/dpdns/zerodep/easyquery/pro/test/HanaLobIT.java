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

import static org.junit.jupiter.api.Assertions.*;

class HanaLobIT {
    @Test
    void materializedValuesPreserveLargeUnicodeBinaryUuidEmptyAndNullRows() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("LOB_VALUES")) {
            var query = HanaTestDatabase.client();
            var client = query.getEasyQueryClient();
            var table = client.getRuntimeContext().getMigrationsSQLGenerator().parseEntity(LobDocument.class);
            table.setSchema(schema.name());
            client.getDatabaseCodeFirst().createTableCommandByMigrationData(List.of(table)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            var large = document(1, "中文😀".repeat(100_000), "ASCII-".repeat(30_000), binary(1_048_589));
            large.setExternalId(UUID.fromString("76038e38-0fc7-44bd-b6ef-842dc8d097a9"));
            var empty = document(2, "", "", new byte[0]);
            var absent = document(3, null, null, null);
            assertEquals(3L, query.insertable(List.of(large, empty, absent)).asSchema(s -> schema.name()).batch(true).executeRows());
            var rows = query.queryable(LobDocument.class).asSchema(s -> schema.name()).orderBy(d -> d.id().asc()).toList();
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
            assertEquals(List.of(large.getExternalId()), query.queryable(LobDocument.class).asSchema(s -> schema.name())
                    .where(d -> d.id().eq(1)).selectColumn(d -> d.externalId()).toList());
            try (var transaction = client.beginTransaction()) {
                var inserted = document(4, "rollback", "rollback", new byte[]{1, 2});
                assertThrows(RuntimeException.class, () -> query.insertable(List.of(inserted, large)).asSchema(s -> schema.name()).batch(true).executeRows());
                transaction.rollback();
            }
            assertEquals(3L, query.queryable(LobDocument.class).asSchema(s -> schema.name()).count());
        }
    }

    @Test
    void jdbcLobInputsAndStreamedResultsRetainCallerOwnershipAndReleaseThePool() throws SQLException, IOException {
        try (var table = HanaTestDatabase.createTable("LOB_STREAM", "\"id\" INTEGER PRIMARY KEY, \"text\" NCLOB, \"ascii\" CLOB, \"data\" BLOB");
             var pool = HanaTestDatabase.pool(1);
             var input = streams(1, "中😀".repeat(70_000), "abcdef".repeat(20_000), binary(1_048_589));
             var empty = streams(2, "", "", new byte[0])) {
            var query = HanaTestDatabase.client(pool);
            var client = query.getEasyQueryClient();
            var absent = new LobDocument.Streams();
            absent.setId(3);
            assertEquals(3L, client.insertable(List.of(input, empty, absent)).asTable(t -> table.name()).batch(true).executeRows());
            assertEquals(1_048_589L, input.getData().length());
            assertEquals(210_000L, input.getText().length());
            try (var transaction = client.beginTransaction()) {
                try (var result = client.queryable(LobDocument.Streams.class).asTable(t -> table.name()).orderBy(d -> d.column("id"), true).toStreamResult(1)) {
                    int count = 0;
                    for (var row : result.getStreamIterable()) {
                        try (row) {
                            if (row.getId() == 1) {
                                try (var text = row.getText().getCharacterStream(); var ascii = row.getAscii().getCharacterStream();
                                     var data = row.getData().getBinaryStream()) {
                                    assertCharacters("中😀".repeat(70_000), text);
                                    assertCharacters("abcdef".repeat(20_000), ascii);
                                    var crc = new CRC32();
                                    byte[] buffer = new byte[8192];
                                    long length = 0;
                                    for (int read; (read = data.read(buffer)) != -1; ) {
                                        crc.update(buffer, 0, read);
                                        length += read;
                                    }
                                    assertEquals(1_048_589L, length);
                                    var expected = new CRC32();
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
        try (var table = HanaTestDatabase.createTable("LOB_EXIT", "\"id\" INTEGER PRIMARY KEY, \"text\" NCLOB, \"ascii\" CLOB, \"data\" BLOB");
             var pool = HanaTestDatabase.pool(1);
             var input = streams(1, "中😀".repeat(30_000), "x", binary(524_301))) {
            var client = HanaTestDatabase.client(pool).getEasyQueryClient();
            client.insertable(input).asTable(t -> table.name()).executeRows();
            for (boolean fail : List.of(false, true)) {
                try {
                    try (var transaction = client.beginTransaction();
                         var result = client.queryable(LobDocument.Streams.class).asTable(t -> table.name()).toStreamResult(1)) {
                        for (var row : result.getStreamIterable()) {
                            try (row; var data = row.getData().getBinaryStream()) {
                                assertEquals(4096, data.readNBytes(4096).length);
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
        try (var table = HanaTestDatabase.createTable("LOB_FAILURE", "\"id\" INTEGER PRIMARY KEY, \"data\" BLOB, \"text\" NCLOB");
             var pool = HanaTestDatabase.pool(1)) {
            var client = HanaTestDatabase.client(pool).getEasyQueryClient();
            var blob = new TrackingBlob(false);
            var broken = new SerialClob(new char[]{'x'}) {
                @Override
                public Reader getCharacterStream() throws javax.sql.rowset.serial.SerialException {
                    throw new javax.sql.rowset.serial.SerialException("cannot open reader");
                }
            };
            try {
                try (var transaction = client.beginTransaction()) {
                    String active = "SELECT COUNT(*) FROM M_ACTIVE_STATEMENTS WHERE CONNECTION_ID=CURRENT_CONNECTION";
                    long before = client.sqlQuery(active, Long.class).get(0);
                    assertThrows(RuntimeException.class, () -> client.sqlExecute("INSERT INTO \"" + table.name() + "\" VALUES (?,?,?)", List.of(1, blob, broken)));
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
            var failingRead = new TrackingBlob(true);
            try {
                assertThrows(RuntimeException.class, () -> client.sqlQuery("SELECT LENGTH(?) FROM DUMMY", Long.class, List.of(failingRead)));
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
        var result = new LobDocument();
        result.setId(id);
        result.setText(text);
        result.setAscii(ascii);
        result.setData(data);
        return result;
    }

    private static LobDocument.Streams streams(int id, String text, String ascii, byte[] data) throws SQLException {
        var result = new LobDocument.Streams();
        result.setId(id);
        result.setText(new SerialClob(text.toCharArray()));
        result.setAscii(new SerialClob(ascii.toCharArray()));
        result.setData(new SerialBlob(data));
        return result;
    }

    private static byte[] binary(int size) {
        var result = new byte[size];
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
