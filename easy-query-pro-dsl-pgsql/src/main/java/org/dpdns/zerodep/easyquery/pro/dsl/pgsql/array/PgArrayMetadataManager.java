package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array;

import com.easy.query.core.configuration.nameconversion.MapKeyNameConversion;
import com.easy.query.core.common.MapColumnNameChecker;
import com.easy.query.core.enums.EntityMetadataTypeEnum;
import com.easy.query.core.inject.ServiceProvider;
import com.easy.query.core.metadata.DefaultEntityMetadataManager;
import com.easy.query.core.metadata.EntityMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Allows the explicit SQL array value to participate in ordinary scalar projections.
 */
public final class PgArrayMetadataManager extends DefaultEntityMetadataManager {
    private final EntityMetadata array = new ArrayMetadata();

    public PgArrayMetadataManager(ServiceProvider services, MapColumnNameChecker checker, MapKeyNameConversion conversion) {
        super(services, checker, conversion);
    }

    @Override
    public EntityMetadata getEntityMetadata(Class<?> type) {
        return type == PgArrayValue.class ? array : super.getEntityMetadata(type);
    }

    @Override
    public List<EntityMetadata> getAllLoadedEntityMetadata() {
        var result = new ArrayList<>(super.getAllLoadedEntityMetadata());
        result.add(array);
        return result;
    }

    private static final class ArrayMetadata extends EntityMetadata {
        private ArrayMetadata() {
            super(PgArrayValue.class);
            entityMetadataType = EntityMetadataTypeEnum.BASIC_TYPE;
        }
    }
}
