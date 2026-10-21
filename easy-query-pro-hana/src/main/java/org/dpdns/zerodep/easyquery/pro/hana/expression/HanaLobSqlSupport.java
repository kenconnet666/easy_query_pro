package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.parser.core.base.scec.core.SQLNativeChainExpressionContext;
import com.easy.query.core.expression.segment.SQLSegment;
import com.easy.query.core.expression.segment.core.TableSQLSegment;
import com.easy.query.core.expression.segment.scec.context.core.SQLNativeExpression;
import com.easy.query.core.expression.segment.scec.expression.SQLSegmentParamExpression;
import com.easy.query.core.expression.visitor.TableVisitor;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.SQLFunctionTranslateImpl;
import com.easy.query.core.util.EasySQLSegmentUtil;

import java.util.Objects;
import java.util.List;
import com.easy.query.core.expression.segment.scec.expression.ParamExpression;

/**
 * Carries a known LOB result through the public native-segment factory without inspecting rendered SQL.
 */
public final class HanaLobSqlSupport {
    private HanaLobSqlSupport() {
    }

    public static IllegalArgumentException unsupportedClause(String clause) {
        return new IllegalArgumentException("HANA " + clause + " cannot use a LOB value; explicitly convert it to a scalar first");
    }

    public static SQLFunction lobResult(SQLFunction delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new SQLFunction() {
            @Override
            public String sqlSegment(TableAvailable table) {
                return "{0}";
            }

            @Override
            public int paramMarks() {
                return 1;
            }

            @Override
            public AggregationType getAggregationType() {
                return delegate.getAggregationType();
            }

            @Override
            public void consume(SQLNativeChainExpressionContext context) {
                SQLSegment segment = new SQLFunctionTranslateImpl(delegate).toSQLSegment(context.getExpressionContext(), context.getDefaultTable(), context.getRuntimeContext(), null);
                context.sql(new LobValue(context.getDefaultTable(), segment));
            }
        };
    }

    static boolean isLobReturn(String template, SQLNativeExpression expression) {
        List<ParamExpression> arguments = expression.getExpressions();
        boolean identity = template.equals("{0}") || template.equals("{0} ASC") || template.equals("{0} DESC");
        if (!identity || arguments.size() != 1 || !(arguments.get(0) instanceof SQLSegmentParamExpression)) return false;
        SQLSegment segment = ((SQLSegmentParamExpression) arguments.get(0)).getSQLSegment();
        return segment instanceof LobValue || HanaSQLSegmentFactory.hasLobResult(segment);
    }

    private static final class LobValue implements TableSQLSegment {
        private final TableAvailable table;
        private final SQLSegment delegate;

        private LobValue(TableAvailable table, SQLSegment delegate) {
            this.table = table;
            this.delegate = delegate;
        }
        @Override
        public TableAvailable getTable() {
            return table;
        }

        @Override
        public LobValue cloneSQLColumnSegment() {
            return this;
        }

        @Override
        public String toSQL(ToSQLContext context) {
            return delegate.toSQL(context);
        }

        @Override
        public void accept(TableVisitor visitor) {
            EasySQLSegmentUtil.sqlSegmentTableVisit(delegate, visitor);
        }

        public TableAvailable table() {
            return table;
        }

        public SQLSegment delegate() {
            return delegate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LobValue)) return false;
            LobValue other = (LobValue) o;
            return Objects.equals(table, other.table)
                    && Objects.equals(delegate, other.delegate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(table, delegate);
        }

        @Override
        public String toString() {
            return "LobValue[table=" + table + ", delegate=" + delegate + "]";
        }
    }
}
