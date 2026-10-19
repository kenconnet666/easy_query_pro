package org.dpdns.zerodep.easyquery.pro.hana.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Explicit connection verification, separate from offline SQL rendering. */
public record HanaDatabaseInfo(String productName, String productVersion, HanaVersion version) {
    public HanaDatabaseInfo {
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(productVersion, "productVersion");
        Objects.requireNonNull(version, "version");
    }

    public static HanaDatabaseInfo inspect(Connection connection) throws SQLException {
        var metadata = Objects.requireNonNull(connection, "connection").getMetaData();
        String product = metadata.getDatabaseProductName();
        if (!"HDB".equalsIgnoreCase(product) && !"SAP HANA".equalsIgnoreCase(product)) {
            throw new IllegalArgumentException("Expected SAP HANA, but JDBC reports " + product);
        }
        String rawVersion = metadata.getDatabaseProductVersion();
        HanaVersion version = HanaVersion.parse(rawVersion);
        if (version.major() != 2 || version.minor() != 0) {
            throw new IllegalArgumentException("This backend targets HANA 2.0; JDBC reports " + rawVersion);
        }
        return new HanaDatabaseInfo(product, rawVersion, version);
    }
}
