package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.Test;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;

import java.sql.SQLException;

class MySqlConnectionIT {
    @Test
    void upstreamAdapterWorksWithTheConfiguredMysql() throws SQLException {
        var dataSource = new MysqlDataSource();
        dataSource.setURL(TestEnvironment.required("EQP_MYSQL_JDBC_URL"));
        dataSource.setUser(TestEnvironment.required("EQP_MYSQL_USER"));
        dataSource.setPassword(TestEnvironment.required("EQP_MYSQL_PASSWORD"));
        dataSource.setConnectTimeout(10_000);
        dataSource.setSocketTimeout(30_000);
        JdbcContract.verify(dataSource, new MySQLDatabaseConfiguration(), CommonTypes.unicodeText(255).ddlType(SqlDialect.MYSQL));
        DecimalDslContract.verify(dataSource, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        NullSafeDslContract.verify(dataSource, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        NullIfDslContract.verify(dataSource, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        TemporalTruncationDslContract.verify(dataSource, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        TextDslContract.verify(dataSource, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
        JsonPathDslContract.verify(dataSource, new MySQLDatabaseConfiguration(), MySqlExtensions::configure);
    }
}
