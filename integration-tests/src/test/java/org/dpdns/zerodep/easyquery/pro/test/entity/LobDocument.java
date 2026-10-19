package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.LobDocumentProxy;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.UUID;

@Table("eqp_document")
@EntityProxy
public class LobDocument implements ProxyEntityAvailable<LobDocument, LobDocumentProxy> {
    @Column(primaryKey = true)
    private Integer id;
    private UUID externalId;
    @Column(dbType = "NCLOB")
    private String text;
    @Column(dbType = "CLOB")
    private String ascii;
    @Column(dbType = "BLOB")
    private byte[] data;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public UUID getExternalId() {
        return externalId;
    }

    public void setExternalId(UUID externalId) {
        this.externalId = externalId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getAscii() {
        return ascii;
    }

    public void setAscii(String ascii) {
        this.ascii = ascii;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    @Table("eqp_document")
    public static class Streams implements AutoCloseable {
        @Column(primaryKey = true)
        private Integer id;
        private Clob text;
        private Clob ascii;
        private Blob data;

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public Clob getText() {
            return text;
        }

        public void setText(Clob text) {
            this.text = text;
        }

        public Clob getAscii() {
            return ascii;
        }

        public void setAscii(Clob ascii) {
            this.ascii = ascii;
        }

        public Blob getData() {
            return data;
        }

        public void setData(Blob data) {
            this.data = data;
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            try {
                if (data != null) data.free();
            } catch (SQLException error) {
                failure = error;
            }
            try {
                if (text != null) text.free();
            } catch (SQLException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            try {
                if (ascii != null) ascii.free();
            } catch (SQLException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
}
