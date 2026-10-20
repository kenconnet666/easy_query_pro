package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.util.List;

/**
 * 方言 frame 共享 OVER 结构，各自校验排序键与偏移的语法和类型。
 * 实现必须不可变；validate/function 不得修改规格、排序列表或其所属查询。
 * WindowSpec 仅冻结自身结构，不深复制上游列表达式或扩展 frame。
 */
public interface WindowFrame {
    void validate(SqlDialect dialect, List<WindowOrder> ordering);

    SQLFunction function(SQLFunc factory, SqlDialect dialect);
}
