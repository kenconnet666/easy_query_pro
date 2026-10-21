package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.internal;

import com.easy.query.core.configuration.nameconversion.MapKeyNameConversion;
import com.easy.query.core.common.MapColumnNameChecker;
import com.easy.query.core.enums.EntityMetadataTypeEnum;
import com.easy.query.core.inject.ServiceProvider;
import com.easy.query.core.metadata.DefaultEntityMetadataManager;
import com.easy.query.core.metadata.EntityMetadata;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayValue;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

/**
 * Adds only the explicit PostgreSQL value types to EasyQuery's scalar metadata.
 */
public final class PgScalarMetadataManager extends DefaultEntityMetadataManager {
    private final Map<Class<?>, EntityMetadata> scalars = ImmutableCollections.<Class<?>, EntityMetadata>mapBuilder()
            .put(PgArrayValue.class, new ScalarMetadata(PgArrayValue.class))
            .put(PgInterval.class, new ScalarMetadata(PgInterval.class))
            .put(PgInterval.Finite.class, new ScalarMetadata(PgInterval.Finite.class))
            .put(PgInterval.Infinity.class, new ScalarMetadata(PgInterval.Infinity.class))
            .put(PgTsVector.class, new ScalarMetadata(PgTsVector.class))
            .put(PgTsQuery.class, new ScalarMetadata(PgTsQuery.class))
            .build();

    public PgScalarMetadataManager(ServiceProvider services, MapColumnNameChecker checker, MapKeyNameConversion conversion) {
        super(services, checker, conversion);
    }

    @Override
    public EntityMetadata getEntityMetadata(Class<?> type) {
        EntityMetadata scalar = scalars.get(type);
        return scalar == null ? super.getEntityMetadata(type) : scalar;
    }

    @Override
    public List<EntityMetadata> getAllLoadedEntityMetadata() {
        ArrayList<EntityMetadata> result = new ArrayList<>(super.getAllLoadedEntityMetadata());
        result.addAll(scalars.values());
        return result;
    }

    private static final class ScalarMetadata extends EntityMetadata {
        private ScalarMetadata(Class<?> type) {
            super(type);
            entityMetadataType = EntityMetadataTypeEnum.BASIC_TYPE;
        }
    }
}
