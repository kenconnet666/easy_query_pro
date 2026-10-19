package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import com.easy.query.core.inject.ServiceCollection;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

public final class HanaExtensions {
    private HanaExtensions() {
    }

    public static void configure(ServiceCollection services) {
        ExtensionInstaller.install(services, SqlDialect.HANA, HanaSQLKeyword.class);
    }
}
