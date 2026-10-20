package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.ProxyEntity;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.json.HanaJsonTextType;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.json.HanaJsonTextExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.json.HanaJsonTextExpressions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.lob.HanaBinaryLobExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.lob.HanaLobExpressions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.lob.HanaTextLobExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.Objects;

public class HanaDsl extends ProDsl {

    protected HanaDsl(ProxyEntity<?, ?> entity) {
        this(new ExpressionScope(entity));
    }

    protected HanaDsl(ExpressionScope scope) {
        super(scope);
        if (scope.dialect() != SqlDialect.HANA) throw new IllegalArgumentException("HanaDsl requires HANA");
    }

    public static HanaDsl of(ProxyEntity<?, ?> entity) {
        return new HanaDsl(entity);
    }

    public <T> HanaJsonTextExpression<T> view(PropTypeColumn<T> source, HanaJsonTextType type) {
        return HanaJsonTextExpressions.view(scope, this, source, type);
    }

    public HanaJsonTextExpression<String> value(JsonInput value, HanaJsonTextType type) {
        return HanaJsonTextExpressions.value(scope, this, value, type);
    }

    public HanaJsonTextExpression<String> nativeCast(PropTypeColumn<?> source, HanaJsonTextType type) {
        return HanaJsonTextExpressions.cast(scope, this, source, type);
    }

    public <T> HanaTextLobExpression<T> view(PropTypeColumn<T> source, HanaTypes.TextLob type) {
        return HanaLobExpressions.view(scope, source, type);
    }

    public <T> HanaBinaryLobExpression<T> view(PropTypeColumn<T> source, HanaTypes.BinaryLob type) {
        return HanaLobExpressions.view(scope, source, type);
    }

    public HanaTextLobExpression<String> value(String value, HanaTypes.CharacterLob type) {
        return view(HanaLobExpressions.value(scope, value, Objects.requireNonNull(type, "type")), type);
    }

    public HanaBinaryLobExpression<byte[]> value(byte[] value, HanaTypes.BinaryLob type) {
        return view(HanaLobExpressions.value(scope, value, Objects.requireNonNull(type, "type")), type);
    }

    public HanaTextLobExpression<String> nativeCast(PropTypeColumn<?> source, HanaTypes.CharacterLob type) {
        return view(HanaLobExpressions.cast(scope, source, Objects.requireNonNull(type, "type")), type);
    }

    public HanaBinaryLobExpression<byte[]> nativeCast(PropTypeColumn<?> source, HanaTypes.BinaryLob type) {
        return view(HanaLobExpressions.cast(scope, source, Objects.requireNonNull(type, "type")), type);
    }
}
