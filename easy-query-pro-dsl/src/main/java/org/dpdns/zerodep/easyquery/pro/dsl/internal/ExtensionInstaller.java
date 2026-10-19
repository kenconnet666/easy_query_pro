package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.util.Objects;

/**
 * Shared installation plumbing for the three extension modules; not an application entry point.
 */
public final class ExtensionInstaller {
    private ExtensionInstaller() {
    }

    public static void install(ServiceCollection services, SqlDialect dialect, Class<? extends SQLKeyword> backend) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(backend, "backend");
        Installed previous = null;
        try {
            // 只读取本类的私有实例标记；不会提前构造 DataSource 或任何业务服务。
            previous = services.build().getService(Installed.class);
        } catch (IllegalArgumentException absent) {
            // EasyQuery 3.2.17 没有 tryGetService；未注册的服务用 IllegalArgumentException 表示。
        }
        if (previous != null) {
            if (previous.dialect() != dialect)
                throw new IllegalStateException("Conflicting Pro extensions: " + previous.dialect() + " and " + dialect);
            return;
        }
        services.addService(Installed.class, new Installed(dialect));
        services.addServiceFactory(SqlDialect.class, provider -> {
            var keyword = provider.getService(SQLKeyword.class);
            if (!backend.isInstance(keyword)) {
                throw new IllegalStateException("Pro " + dialect + " extensions require backend " + backend.getName() + "; found " + keyword.getClass().getName());
            }
            return dialect;
        });
    }

    private record Installed(SqlDialect dialect) {
    }
}
