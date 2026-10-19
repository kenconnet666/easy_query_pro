package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

final class TestClients {
    private TestClients() {
    }

    static DefaultEasyEntityQuery offline(DatabaseConfiguration configuration) {
        var dataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    throw new AssertionError("SQL rendering must not access JDBC: " + method.getName());
                });
        var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(dataSource).useDatabaseConfigure(configuration).build();
        return new DefaultEasyEntityQuery(client);
    }
}
