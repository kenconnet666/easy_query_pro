package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.util.List;

/**
 * Dialect frames share the OVER structure and retain their own ordering/offset rules.
 */
public interface WindowFrame {
    void validate(SqlDialect dialect, List<WindowOrder> ordering);

    SQLFunction function(SQLFunc factory, SqlDialect dialect);
}
