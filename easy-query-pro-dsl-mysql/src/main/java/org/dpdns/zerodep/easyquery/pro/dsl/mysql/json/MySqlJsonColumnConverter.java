package org.dpdns.zerodep.easyquery.pro.dsl.mysql.json;

import org.dpdns.zerodep.easyquery.pro.dsl.internal.NativeJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;

public final class MySqlJsonColumnConverter extends NativeJsonColumnConverter {
    public MySqlJsonColumnConverter() {
        super(MySqlTypes.json());
    }
}
