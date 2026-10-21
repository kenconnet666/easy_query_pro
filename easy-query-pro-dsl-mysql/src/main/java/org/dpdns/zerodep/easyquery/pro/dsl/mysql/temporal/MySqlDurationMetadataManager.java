package org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal;

import com.easy.query.core.common.MapColumnNameChecker;
import com.easy.query.core.configuration.nameconversion.MapKeyNameConversion;
import com.easy.query.core.enums.EntityMetadataTypeEnum;
import com.easy.query.core.inject.ServiceProvider;
import com.easy.query.core.metadata.DefaultEntityMetadataManager;
import com.easy.query.core.metadata.EntityMetadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * EasyQuery 3.2.17 omits Duration from its scalar type list.
 */
public final class MySqlDurationMetadataManager extends DefaultEntityMetadataManager {
    private final EntityMetadata duration = new DurationMetadata();

    public MySqlDurationMetadataManager(ServiceProvider services, MapColumnNameChecker checker, MapKeyNameConversion conversion) {
        super(services, checker, conversion);
    }

    @Override
    public EntityMetadata getEntityMetadata(Class<?> type) {
        return type == Duration.class ? duration : super.getEntityMetadata(type);
    }

    @Override
    public List<EntityMetadata> getAllLoadedEntityMetadata() {
        ArrayList<EntityMetadata> result = new ArrayList<>(super.getAllLoadedEntityMetadata());
        result.add(duration);
        return result;
    }

    private static final class DurationMetadata extends EntityMetadata {
        private DurationMetadata() {
            super(Duration.class);
            entityMetadataType = EntityMetadataTypeEnum.BASIC_TYPE;
        }
    }
}
