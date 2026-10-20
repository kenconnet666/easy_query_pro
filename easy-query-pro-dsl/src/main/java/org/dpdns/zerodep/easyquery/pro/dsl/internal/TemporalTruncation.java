package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.column.ColumnExpression;
import com.easy.query.core.func.def.AnySQLFunction;
import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;

import java.util.Collections;

/**
 * Database translations use existing column expressions, never rendered SQL.
 */
public interface TemporalTruncation {
    SQLFunction truncate(ColumnExpression source, TruncationUnit unit, boolean dateOnly);

    record Template(String sql, int occurrences) {
        public Template castTo(String type) {
            return new Template("CAST(" + sql + " AS " + type + ")", occurrences);
        }

        public SQLFunction bind(ColumnExpression source) {
            // 每次 SQL 引用都有独立参数槽，不能重复 {0} 却只绑定一次 JDBC 参数。
            return new AnySQLFunction(sql, Collections.nCopies(occurrences, source));
        }
    }
}
