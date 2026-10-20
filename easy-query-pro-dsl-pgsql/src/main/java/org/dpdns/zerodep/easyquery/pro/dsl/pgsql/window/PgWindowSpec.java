package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.window;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RangeBoundary;
import org.dpdns.zerodep.easyquery.pro.dsl.window.RangeFrame;
import org.dpdns.zerodep.easyquery.pro.dsl.window.WindowSpec;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * PostgreSQL 原生 RANGE 规格，沿用共同不可变状态和 SELF 重建约定。
 *
 * <p>数值偏移区分 BIGINT 与 NUMERIC，避免整型排序键找不到匹配的 in_range 运算。
 * 时间偏移复用 PgInterval.Finite，保留月份、自然日和微秒三个分量；使用前需按 PG 扩展的
 * 常规安装步骤注册 interval handler。当前只开放非负分量和 LocalDate/LocalDateTime 键。
 *
 * @param <S> 具体规格类型；子类实现 recreate 后，连续调用共同/原生方法仍返回自身类型
 */
public abstract class PgWindowSpec<S extends PgWindowSpec<S>> extends WindowSpec<S> {
    protected PgWindowSpec(ExpressionScope scope) {
        super(scope);
        requirePostgres(scope);
    }

    protected PgWindowSpec(ExpressionScope scope, State state) {
        super(scope, state);
        requirePostgres(scope);
    }

    public static PgWindowSpec<?> create(ExpressionScope scope) {
        return new Default(scope);
    }

    /**
     * 整数键使用整数偏移；NUMERIC 键可以使用 BigDecimal，偏移不会被统一转换为小数。
     */
    public S rangeNumericBetween(RangeBoundary<? extends Number> start, RangeBoundary<? extends Number> end) {
        return frame(new Numeric(start, end));
    }

    /**
     * 日历距离窗口；泛型排除 infinity，混合正负分量在执行前拒绝。
     */
    public S rangeIntervalBetween(RangeBoundary<PgInterval.Finite> start, RangeBoundary<PgInterval.Finite> end) {
        return frame(new Temporal(start, end));
    }

    private static void requirePostgres(ExpressionScope scope) {
        if (scope.dialect() != SqlDialect.POSTGRESQL)
            throw new IllegalArgumentException("PostgreSQL windows require PostgreSQL");
    }

    private static class Default extends PgWindowSpec<Default> {
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
            super(SqlDialect.POSTGRESQL, start, end);
        }

        @Override
        protected void validateKey(PropTypeColumn<?> key) {
            var type = numericKey(key);
            if (type != BigDecimal.class && type != BigInteger.class && (start.offset() instanceof BigDecimal || end.offset() instanceof BigDecimal))
                throw new IllegalArgumentException("PostgreSQL integral RANGE keys require integral offsets");
        }

        @Override
        protected void validateOffset(Number value) {
            numericOffset(value);
        }

        @Override
        protected SQLFunction offset(SQLFunc factory, Number value) {
            var parameter = numericOffset(value);
            String type = parameter instanceof BigDecimal ? "NUMERIC" : "BIGINT";
            return factory.anySQLFunction("CAST({0} AS " + type + ")", args -> args.value(parameter));
        }
    }

    private static class Temporal extends RangeFrame<PgInterval.Finite> {
        private Temporal(RangeBoundary<PgInterval.Finite> start, RangeBoundary<PgInterval.Finite> end) {
            super(SqlDialect.POSTGRESQL, start, end);
        }

        @Override
        protected void validateKey(PropTypeColumn<?> key) {
            temporalKey(key);
        }

        @Override
        protected void validateOffset(PgInterval.Finite value) {
            if (value.months() < 0 || value.days() < 0 || value.microseconds() < 0)
                throw new IllegalArgumentException("PostgreSQL RANGE interval components must be nonnegative");
        }

        @Override
        protected SQLFunction offset(SQLFunc factory, PgInterval.Finite value) {
            return factory.anySQLFunction("CAST({0} AS INTERVAL)", args -> args.value(value));
        }
    }
}
