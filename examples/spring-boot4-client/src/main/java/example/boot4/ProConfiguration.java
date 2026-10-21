package example.boot4;

import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.bootstrapper.StarterConfigurer;
import com.easy.query.sql.starter.SpringBootStarterBuilder;
import com.easy.query.sql.starter.config.EasyQueryInitializeOption;
import com.easy.query.sql.starter.config.EasyQueryProperties;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 应用配置示例，不是新的 starter。上游 builder 继续负责原生选项与 Spring 连接管理。
 */
@Configuration(proxyBeanMethods = false)
public class ProConfiguration {
    @Bean
    StarterConfigurer proServices(EasyQueryProperties properties, ObjectProvider<HanaOptions> hanaOptions) {
        return services -> {
            switch (properties.getDatabase()) {
                case MYSQL -> {
                    MySqlExtensions.configure(services);
                    MySqlExtensions.configureQueryScope(services);
                }
                case PGSQL -> {
                    PgSqlExtensions.configure(services);
                    PgSqlExtensions.configureQueryScope(services);
                }
                case SQL92 -> {
                    // 3.2.17 的 SQL92 配置为空钩子；在 build 前安装完整 HANA 服务，不能保留 UNKNOWN。
                    new HanaDatabaseConfiguration(hanaOptions.getIfAvailable(() -> HanaOptions.DEFAULT)).configure(services);
                    HanaExtensions.configure(services);
                }
                default ->
                        throw new IllegalArgumentException("This example supports MYSQL, PGSQL and SQL92 with the HANA adapter");
            }
        };
    }

    @Bean
    EasyQueryClient easyQueryClient(DataSource source, EasyQueryProperties properties,
                                    EasyQueryInitializeOption applicationComponents, StarterConfigurer services) {
        // 暂缓组件安装，确保默认 handler 先就绪；converter 按类注册，先保留应用实例再补默认项。
        var client = SpringBootStarterBuilder.buildClient(source, properties, null, services);
        var runtime = client.getRuntimeContext();
        try {
            switch (properties.getDatabase()) {
                case MYSQL -> MySqlExtensions.configureTypeHandlers(runtime.getJdbcTypeHandlerManager());
                case PGSQL -> PgSqlExtensions.configureTypeHandlers(runtime.getJdbcTypeHandlerManager());
                case SQL92 -> {
                    // HANA 的基础 JDBC 映射由完整 DatabaseConfiguration 注册。
                }
                default -> throw new IllegalStateException("Unsupported database passed the starter configuration");
            }
            applicationComponents.addComponents(client);
            switch (properties.getDatabase()) {
                case MYSQL -> MySqlExtensions.configureMappings(runtime.getQueryConfiguration());
                case PGSQL -> PgSqlExtensions.configureMappings(runtime.getQueryConfiguration());
            }
            return client;
        } catch (RuntimeException | Error failure) {
            // 工厂尚未成功返回，Spring 的销毁回调还没有接管这个 client。
            try {
                stopWorkers(client);
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Bean
    DisposableBean easyQueryWorkers(EasyQueryClient client) {
        // 先关闭本 client 的工作任务；DataSource 由其拥有者（通常是 Spring/Hikari）关闭。
        return () -> stopWorkers(client);
    }

    private static void stopWorkers(EasyQueryClient client) {
        var runtime = client.getRuntimeContext();
        try {
            runtime.getEasyTimeJobManager().shutdown();
        } catch (RuntimeException | Error failure) {
            try {
                runtime.getShardingExecutorService().getExecutorService().shutdown();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        runtime.getShardingExecutorService().getExecutorService().shutdown();
    }
}
