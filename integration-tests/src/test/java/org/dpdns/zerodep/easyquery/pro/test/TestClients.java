package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.inject.ServiceCollection;

import java.util.function.Consumer;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import com.easy.query.core.api.client.EasyQueryClient;

final class TestClients {
    private TestClients() {
    }

    static DefaultEasyEntityQuery offline(DatabaseConfiguration configuration) {
        return offline(configuration, services -> {
        });
    }

    static DefaultEasyEntityQuery offline(DatabaseConfiguration configuration, Consumer<ServiceCollection> extensions) {
        DataSource dataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    throw new AssertionError("SQL rendering must not access JDBC: " + method.getName());
                });
        return configured(dataSource, configuration, extensions);
    }

    static DefaultEasyEntityQuery configured(DataSource dataSource, DatabaseConfiguration configuration, Consumer<ServiceCollection> extensions) {
        EasyQueryClient client = EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(dataSource).useDatabaseConfigure(configuration).customConfigure(extensions).build();
        return new DefaultEasyEntityQuery(client);
    }
}
