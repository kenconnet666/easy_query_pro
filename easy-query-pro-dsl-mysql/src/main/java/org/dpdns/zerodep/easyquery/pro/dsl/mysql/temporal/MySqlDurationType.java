package org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal;

import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CastTarget;
import org.dpdns.zerodep.easyquery.pro.dsl.type.StorageType;

import java.time.Duration;
import java.util.Objects;

/**
 * 将 MySQL TIME 映射为有符号 Duration，保留其与一天内 LocalTime 的区别。
 * 参数必须落在本包已验证的 TIME 值域及声明 fsp 刻度上；不自动舍弃尾部纳秒或作舍入。
 * 该预校验只针对 typed value，不能替代数据库表达式和 nativeCast 的原生行为。
 */
public final class MySqlDurationType implements StorageType<Duration>, CastTarget<Duration> {
    private final int fractionalPrecision;

    private static final Duration MAXIMUM = Duration.ofSeconds(3_020_399);
    private static final Duration MINIMUM = MAXIMUM.negated();

    public MySqlDurationType(int fractionalPrecision) {
        if (fractionalPrecision < 0 || fractionalPrecision > 6)
            throw new IllegalArgumentException("MySQL TIME fractional precision must be between 0 and 6");
        this.fractionalPrecision = fractionalPrecision;
    }

    @Override
    public Class<Duration> javaType() {
        return Duration.class;
    }

    @Override
    public String ddlType(SqlDialect dialect) {
        if (dialect != SqlDialect.MYSQL) throw new IllegalArgumentException("TIME duration requires MySQL");
        return "TIME(" + fractionalPrecision + ")";
    }

    @Override
    public String castTemplate(SqlDialect dialect) {
        return "CAST({0} AS " + ddlType(dialect) + ")";
    }

    @Override
    public Duration prepareValue(Duration value) {
        if (value == null) return null;
        if (value.compareTo(MINIMUM) < 0 || value.compareTo(MAXIMUM) > 0)
            throw new IllegalArgumentException("MySQL TIME duration must be within +/-838:59:59");
        int quantum = 1;
        for (int i = fractionalPrecision; i < 9; i++) quantum *= 10;
        if (value.getNano() % quantum != 0)
            throw new IllegalArgumentException("Duration is not exactly representable as TIME(" + fractionalPrecision + ")");
        return value;
    }

    public int fractionalPrecision() {
        return fractionalPrecision;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MySqlDurationType)) return false;
        MySqlDurationType other = (MySqlDurationType) o;
        return fractionalPrecision == other.fractionalPrecision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fractionalPrecision);
    }

    @Override
    public String toString() {
        return "MySqlDurationType[fractionalPrecision=" + fractionalPrecision + "]";
    }
}
