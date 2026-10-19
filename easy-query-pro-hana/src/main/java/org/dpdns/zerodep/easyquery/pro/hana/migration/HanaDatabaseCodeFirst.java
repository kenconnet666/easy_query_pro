package org.dpdns.zerodep.easyquery.pro.hana.migration;

import com.easy.query.core.basic.api.database.CodeFirstCommand;
import com.easy.query.core.basic.api.database.DefaultDatabaseCodeFirst;
import com.easy.query.core.inject.ServiceProvider;
import com.easy.query.core.migration.MigrationCommand;

import java.util.List;

public final class HanaDatabaseCodeFirst extends DefaultDatabaseCodeFirst {
    public HanaDatabaseCodeFirst(ServiceProvider services) {
        super(services);
    }

    @Override
    public CodeFirstCommand createCodeFirstCommand(List<MigrationCommand> commands) {
        return new HanaCodeFirstCommand(getRuntimeContext(), commands);
    }
}
