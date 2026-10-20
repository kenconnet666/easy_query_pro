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

/**
 * Adds only the explicit PostgreSQL value types to EasyQuery's scalar metadata.
 */
public final class PgScalarMetadataManager extends DefaultEntityMetadataManager {
    private final Map<Class<?>, EntityMetadata> scalars = Map.of(
            PgArrayValue.class, new ScalarMetadata(PgArrayValue.class),
            PgInterval.class, new ScalarMetadata(PgInterval.class),
            PgInterval.Finite.class, new ScalarMetadata(PgInterval.Finite.class),
            PgInterval.Infinity.class, new ScalarMetadata(PgInterval.Infinity.class),
            PgTsVector.class, new ScalarMetadata(PgTsVector.class),
            PgTsQuery.class, new ScalarMetadata(PgTsQuery.class));

    public PgScalarMetadataManager(ServiceProvider services, MapColumnNameChecker checker, MapKeyNameConversion conversion) {
        super(services, checker, conversion);
    }

    @Override
    public EntityMetadata getEntityMetadata(Class<?> type) {
        var scalar = scalars.get(type);
        return scalar == null ? super.getEntityMetadata(type) : scalar;
    }

    @Override
    public List<EntityMetadata> getAllLoadedEntityMetadata() {
        var result = new ArrayList<>(super.getAllLoadedEntityMetadata());
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
