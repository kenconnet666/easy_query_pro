package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PrimitiveScalarsProxy;

@EntityProxy
@Table("eqp_primitive_scalars")
public class PrimitiveScalars implements ProxyEntityAvailable<PrimitiveScalars, PrimitiveScalarsProxy> {
    private int number;
    private boolean enabled;

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
