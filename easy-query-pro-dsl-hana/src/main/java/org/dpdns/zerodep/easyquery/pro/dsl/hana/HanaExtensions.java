package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NullSafeComparison;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

public final class HanaExtensions {
    private HanaExtensions() {
    }

    public static void configure(ServiceCollection services) {
        // MAP 将两个 NULL 匹配，并且 SQL 中每个输入只出现一次。
        ExtensionInstaller.install(services, SqlDialect.HANA, HanaSQLKeyword.class,
                new NullSafeComparison("(CASE MAP({0},{1},1,0) WHEN 1 THEN TRUE ELSE FALSE END)",
                        "(CASE MAP({0},{1},1,0) WHEN 1 THEN FALSE ELSE TRUE END)"), HanaProSQLSegmentFactory.class);
        services.addService(TemporalTruncation.class, new HanaTemporalTruncation());
        services.addServiceFactory(JsonPathAccess.class, provider -> new HanaJsonPathAccess(provider.getService(HanaOptions.class)));
    }
}
