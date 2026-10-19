package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.sap.db.jdbcext.HanaDataSource;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

final class HanaTestDatabase {
    private HanaTestDatabase() {
    }

    static DataSource dataSource() throws SQLException {
        var dataSource = new HanaDataSource();
        dataSource.setUrl(TestEnvironment.required("EQP_HANA_JDBC_URL"));
        dataSource.setUser(TestEnvironment.required("EQP_HANA_USER"));
        dataSource.setPassword(TestEnvironment.required("EQP_HANA_PASSWORD"));
        dataSource.setLoginTimeout(10);
        return dataSource;
    }

    static Connection connect() throws SQLException {
        return dataSource().getConnection();
    }

    static DefaultEasyEntityQuery client() throws SQLException {
        return client(HanaOptions.DEFAULT);
    }

    static DefaultEasyEntityQuery client(HanaOptions options) throws SQLException {
        // 每次构建独立 client，使函数选项与服务注册不会在测试之间相互污染。
        return new DefaultEasyEntityQuery(EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(dataSource())
                .useDatabaseConfigure(new HanaDatabaseConfiguration(options)).build());
    }

    static String uniqueName(String prefix) {
        return "EQP_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
