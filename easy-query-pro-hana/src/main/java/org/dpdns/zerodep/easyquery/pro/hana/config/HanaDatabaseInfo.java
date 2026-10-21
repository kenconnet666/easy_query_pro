package org.dpdns.zerodep.easyquery.pro.hana.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.sql.DatabaseMetaData;

/** Explicit connection verification, separate from offline SQL rendering. */
public final class HanaDatabaseInfo {
    private final String productName;
    private final String productVersion;
    private final HanaVersion version;

    public HanaDatabaseInfo(String productName, String productVersion, HanaVersion version) {
        Objects.requireNonNull(productName, "productName");
        Objects.requireNonNull(productVersion, "productVersion");
        Objects.requireNonNull(version, "version");
        this.productName = productName;
        this.productVersion = productVersion;
        this.version = version;
    }

    public static HanaDatabaseInfo inspect(Connection connection) throws SQLException {
        DatabaseMetaData metadata = Objects.requireNonNull(connection, "connection").getMetaData();
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

    public String productName() {
        return productName;
    }

    public String productVersion() {
        return productVersion;
    }

    public HanaVersion version() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HanaDatabaseInfo)) return false;
        HanaDatabaseInfo other = (HanaDatabaseInfo) o;
        return Objects.equals(productName, other.productName)
                && Objects.equals(productVersion, other.productVersion)
                && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productName, productVersion, version);
    }

    @Override
    public String toString() {
        return "HanaDatabaseInfo[productName=" + productName + ", productVersion=" + productVersion + ", version=" + version + "]";
    }
}
