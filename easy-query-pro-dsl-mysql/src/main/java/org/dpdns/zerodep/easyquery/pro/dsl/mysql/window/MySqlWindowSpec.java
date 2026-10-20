package org.dpdns.zerodep.easyquery.pro.dsl.mysql.window;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RangeBoundary;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RangeFrame;
import org.dpdns.zerodep.easyquery.pro.dsl.window.WindowSpec;

/**
 * MySQL 原生窗口规格，在共同 partition/order/ROWS 之上增加 RANGE。
 *
 * <p>通过 {@code MySqlDsl.nativeWindow()} 获得默认实现；应用可继承本类并把 S 设为自身类型，
 * 实现 recreate 后使用 {@code pro.window(CustomWindow::new)} 挂载。每次链式操作创建新规格，
 * 原规格可安全用于同一查询中的另一个窗口。
 *
 * @param <S> 应用或默认实现的具体类型，保证共同方法与方言方法调用后仍可继续使用自定义方法
 */
public abstract class MySqlWindowSpec<S extends MySqlWindowSpec<S>> extends WindowSpec<S> {
    protected MySqlWindowSpec(ExpressionScope scope) {
        super(scope);
        requireMySql(scope);
    }

    protected MySqlWindowSpec(ExpressionScope scope, State state) {
        super(scope, state);
        requireMySql(scope);
    }

    public static MySqlWindowSpec<?> create(ExpressionScope scope) {
        return new Default(scope);
    }

    /**
     * 数值距离窗口；有限偏移要求一个排序键，整数与 BigDecimal 按原生参数语法绑定。
     */
    public S rangeNumericBetween(RangeBoundary<? extends Number> start, RangeBoundary<? extends Number> end) {
        return frame(new Numeric(start, end));
    }

    /**
     * 日期/时间距离窗口；按距离选行，不能用“前 N 行”的 ROWS 语义替代。
     */
    public S rangeIntervalBetween(RangeBoundary<MySqlRangeInterval> start, RangeBoundary<MySqlRangeInterval> end) {
        return frame(new Temporal(start, end));
    }

    private static void requireMySql(ExpressionScope scope) {
        if (scope.dialect() != SqlDialect.MYSQL) throw new IllegalArgumentException("MySQL windows require MySQL");
    }

    private static class Default extends MySqlWindowSpec<Default> {
        private Default(ExpressionScope scope) {
            super(scope);
        }

        private Default(ExpressionScope scope, State state) {
            super(scope, state);
        }

        @Override
        protected Default recreate(State state) {
            return new Default(scope, state);
        }
    }

    private static class Numeric extends RangeFrame<Number> {
        private Numeric(RangeBoundary<? extends Number> start, RangeBoundary<? extends Number> end) {
            super(SqlDialect.MYSQL, start, end);
        }

        @Override
        protected void validateKey(PropTypeColumn<?> key) {
            numericKey(key);
        }

        @Override
        protected void validateOffset(Number value) {
            numericOffset(value);
        }

        @Override
        protected SQLFunction offset(SQLFunc factory, Number value) {
            return factory.anySQLFunction("{0}", args -> args.value(numericOffset(value)));
        }
    }

    private static class Temporal extends RangeFrame<MySqlRangeInterval> {
        private Temporal(RangeBoundary<MySqlRangeInterval> start, RangeBoundary<MySqlRangeInterval> end) {
            super(SqlDialect.MYSQL, start, end);
        }

        @Override
        protected void validateKey(PropTypeColumn<?> key) {
            temporalKey(key);
        }

        @Override
        protected void validateOffset(MySqlRangeInterval value) {
        }

        @Override
        protected SQLFunction offset(SQLFunc factory, MySqlRangeInterval value) {
            return factory.anySQLFunction("INTERVAL {0} " + value.sqlUnit(), args -> args.value(value.amount()));
        }
    }
}
