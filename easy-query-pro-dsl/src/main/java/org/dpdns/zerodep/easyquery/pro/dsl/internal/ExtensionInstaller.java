package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.core.configuration.QueryConfiguration;
import com.easy.query.core.basic.extension.conversion.ColumnValueSQLConverter;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.expression.parser.factory.SQLExpressionInvokeFactory;
import com.easy.query.core.expression.sql.builder.factory.ExpressionBuilderFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProProjectionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProExpressionBuilderFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProSQLSegmentFactory;
import com.easy.query.core.expression.segment.factory.SQLSegmentFactory;
import com.easy.query.core.expression.predicate.SmartPredicateAnonymousExpressionBuilderProvider;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProSmartPredicateProvider;

import java.util.Objects;

/**
 * Shared installation plumbing for the three extension modules; not an application entry point.
 */
public final class ExtensionInstaller {
    private ExtensionInstaller() {
    }

    /**
     * 默认映射只补缺项。上游按具体类拒绝重复注册，因此不能覆盖应用已经提供的同类实例。
     * 与上游注册一样，应在首次实体 metadata 加载前完成。
     */
    public static void installDefaultMapping(QueryConfiguration configuration, ColumnValueSQLConverter converter) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(converter, "converter");
        if (configuration.getColumnValueSQLConverter(converter.getClass()) == null)
            configuration.applyColumnValueSQLConverter(converter);
    }

    public static void install(ServiceCollection services, SqlDialect dialect, Class<? extends SQLKeyword> backend, NullSafeComparison comparison) {
        install(services, dialect, backend, comparison, ProSQLSegmentFactory.class);
    }

    public static void install(ServiceCollection services, SqlDialect dialect, Class<? extends SQLKeyword> backend,
                               NullSafeComparison comparison, Class<? extends SQLSegmentFactory> segments) {
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(segments, "segments");
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
        services.addService(SQLExpressionInvokeFactory.class, ProProjectionFactory.class);
        services.addService(ExpressionBuilderFactory.class, ProExpressionBuilderFactory.class);
        services.addService(SQLSegmentFactory.class, segments);
        services.addService(SmartPredicateAnonymousExpressionBuilderProvider.class, ProSmartPredicateProvider.class);
        services.addService(NullSafeComparison.class, comparison);
        services.addServiceFactory(SqlDialect.class, provider -> {
            SQLKeyword keyword = provider.getService(SQLKeyword.class);
            if (!backend.isInstance(keyword)) {
                throw new IllegalStateException("Pro " + dialect + " extensions require backend " + backend.getName() + "; found " + keyword.getClass().getName());
            }
            return dialect;
        });
    }

    private static final class Installed {
        private final SqlDialect dialect;

        private Installed(SqlDialect dialect) {
            this.dialect = dialect;
        }

        public SqlDialect dialect() {
            return dialect;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Installed)) return false;
            Installed other = (Installed) o;
            return Objects.equals(dialect, other.dialect);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dialect);
        }

        @Override
        public String toString() {
            return "Installed[dialect=" + dialect + "]";
        }
    }
}
