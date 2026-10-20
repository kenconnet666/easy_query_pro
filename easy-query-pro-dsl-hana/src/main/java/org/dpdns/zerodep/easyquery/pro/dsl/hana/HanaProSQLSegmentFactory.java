package org.dpdns.zerodep.easyquery.pro.dsl.hana;

import com.easy.query.core.expression.segment.SQLSegment;
import com.easy.query.core.expression.segment.SelectCountDistinctSegment;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.segment.CloneableSQLSegment;
import com.easy.query.core.expression.segment.SubQueryColumnSegment;
import com.easy.query.core.expression.segment.SQLNativeSegment;
import com.easy.query.core.expression.segment.scec.context.core.SQLNativeExpression;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProSQLSegmentFactory;
import org.dpdns.zerodep.easyquery.pro.hana.expression.HanaSQLSegmentFactory;

import java.util.Collection;

/**
 * Adds the Pro window boundary check while retaining the HANA LOB segment behavior.
 */
public class HanaProSQLSegmentFactory extends HanaSQLSegmentFactory {
    @Override
    public SQLNativeSegment createSQLNativeSegment(ExpressionContext context, String template, SQLNativeExpression expression) {
        return ProSQLSegmentFactory.nativeBoundary(super.createSQLNativeSegment(context, template, expression), expression);
    }

    @Override
    public CloneableSQLSegment createSQLColumnAsSegment(CloneableSQLSegment expression, String alias, QueryRuntimeContext runtime) {
        return ProSQLSegmentFactory.transparentAlias(expression, alias, runtime);
    }

    @Override
    public SubQueryColumnSegment createSubQueryColumnSegment(TableAvailable table, Query<?> query, String alias, QueryRuntimeContext runtime) {
        return ProSQLSegmentFactory.scalarSubquery(table, query, alias, runtime);
    }

    @Override
    public SelectCountDistinctSegment createSelectCountDistinctSegment(Collection<SQLSegment> columns) {
        ProSQLSegmentFactory.validateDistinctCount(columns);
        return super.createSelectCountDistinctSegment(columns);
    }
}
