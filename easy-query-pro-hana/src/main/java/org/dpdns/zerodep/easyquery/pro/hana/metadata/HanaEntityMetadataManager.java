package org.dpdns.zerodep.easyquery.pro.hana.metadata;

import com.easy.query.core.common.MapColumnNameChecker;
import com.easy.query.core.configuration.nameconversion.MapKeyNameConversion;
import com.easy.query.core.enums.EntityMetadataTypeEnum;
import com.easy.query.core.inject.ServiceProvider;
import com.easy.query.core.metadata.DefaultEntityMetadataManager;
import com.easy.query.core.metadata.EntityMetadata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds the HANA scalar Java types omitted by 3.2.17 through the public metadata extension point.
 */
public final class HanaEntityMetadataManager extends DefaultEntityMetadataManager {
    private final EntityMetadata instant = new ScalarMetadata(Instant.class);
    private final EntityMetadata binary = new ScalarMetadata(byte[].class);

    public HanaEntityMetadataManager(ServiceProvider services, MapColumnNameChecker checker, MapKeyNameConversion conversion) {
        super(services, checker, conversion);
    }

    @Override
    public EntityMetadata getEntityMetadata(Class<?> type) {
        if (type == Instant.class) return instant;
        if (type == byte[].class) return binary;
        return super.getEntityMetadata(type);
    }

    @Override
    public List<EntityMetadata> getAllLoadedEntityMetadata() {
        var result = new ArrayList<>(super.getAllLoadedEntityMetadata());
        result.add(instant);
        result.add(binary);
        return result;
    }

    private static final class ScalarMetadata extends EntityMetadata {
        private ScalarMetadata(Class<?> type) {
            super(type);
            entityMetadataType = EntityMetadataTypeEnum.BASIC_TYPE;
        }
    }
}
