package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.func.def.enums.OrderByModeEnum;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaTypedLob;
import org.junit.jupiter.api.Test;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.api.select.Query;

import static org.junit.jupiter.api.Assertions.*;

class HanaLobDslTest {
    @Test
    void lobReturnTypeSurvivesDistinctAndCloneWhileScalarConversionRemainsLegal() {
        DefaultEasyEntityQuery client = TestClients.offline(new HanaDatabaseConfiguration(), HanaExtensions::configure);
        Query<String> selected = client.queryable(HanaTypedLob.class).selectColumn(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()));
        assertThrows(IllegalArgumentException.class, () -> selected.cloneQueryable().distinct().toSQL());
        assertDoesNotThrow(() -> {
            selected.toSQL();
        });
        assertThrows(IllegalArgumentException.class, () -> selected.distinct().toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(HanaTypedLob.class)
                .groupBy(p -> GroupKeys.of(HanaDsl.of(p).view(p.text(), HanaTypes.nclob()))).toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(HanaTypedLob.class)
                .orderBy(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).asc()).toSQL());
        assertThrows(IllegalArgumentException.class, () -> client.queryable(HanaTypedLob.class)
                .orderBy(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).desc(OrderByModeEnum.NULLS_FIRST)).toSQL());
        assertDoesNotThrow(() -> client.queryable(HanaTypedLob.class)
                .orderBy(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).asc(false)).toSQL());
        assertDoesNotThrow(() -> client.queryable(HanaTypedLob.class).orderBy(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).asText().asc()).toSQL());
        assertDoesNotThrow(() -> client.queryable(HanaTypedLob.class)
                .selectColumn(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).asText()).distinct().toSQL());
        assertDoesNotThrow(() -> client.queryable(HanaTypedLob.class)
                .groupBy(p -> GroupKeys.of(HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).asText())).toSQL());
    }

    @Test
    void knownStorageAndSliceErrorsFailBeforeJdbc() {
        DefaultEasyEntityQuery client = TestClients.offline(new HanaDatabaseConfiguration(), HanaExtensions::configure);
        assertThrows(IllegalArgumentException.class, () -> client.queryable(HanaTypedLob.class).selectColumn(p -> HanaDsl.of(p).view(p.plain(), HanaTypes.nclob())));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(HanaTypedLob.class).selectColumn(p ->
                ProDsl.of(p).nullSafeEqualsValue(HanaDsl.of(p).view(p.text(), HanaTypes.nclob()), "text")));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(HanaTypedLob.class).selectColumn(p -> HanaDsl.of(p).view(p.data(), HanaTypes.blob()).sliceBytes(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> client.queryable(HanaTypedLob.class).selectColumn(p -> HanaDsl.of(p).view(p.text(), HanaTypes.nclob()).sliceUtf16(1, 5001)));
        assertThrows(IllegalArgumentException.class, () -> HanaTypes.clob().prepareValue("中"));
    }
}
