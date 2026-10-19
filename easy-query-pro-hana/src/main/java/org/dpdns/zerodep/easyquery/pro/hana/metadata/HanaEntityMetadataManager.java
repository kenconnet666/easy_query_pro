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
 * 3.2.17 的基础类型列表未包含 Instant，使用公开 metadata 扩展点保留其标量语义。
 */
public final class HanaEntityMetadataManager extends DefaultEntityMetadataManager {
    private final EntityMetadata instant = new InstantMetadata();

    public HanaEntityMetadataManager(ServiceProvider services, MapColumnNameChecker checker, MapKeyNameConversion conversion) {
        super(services, checker, conversion);
    }

    @Override
    public EntityMetadata getEntityMetadata(Class<?> type) {
        return type == Instant.class ? instant : super.getEntityMetadata(type);
    }

    @Override
    public List<EntityMetadata> getAllLoadedEntityMetadata() {
        var result = new ArrayList<>(super.getAllLoadedEntityMetadata());
        result.add(instant);
        return result;
    }

    private static final class InstantMetadata extends EntityMetadata {
        private InstantMetadata() {
            super(Instant.class);
            entityMetadataType = EntityMetadataTypeEnum.BASIC_TYPE;
        }
    }
}
