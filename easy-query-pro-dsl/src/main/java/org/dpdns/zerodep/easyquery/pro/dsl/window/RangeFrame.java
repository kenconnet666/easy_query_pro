package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.util.EasyClassUtil;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * RANGE 的共同语法骨架，供各方言的数值 frame 和时间 frame 复用。
 *
 * <p>本层负责边界类别、单个排序项要求以及结构化 SQLFunction 的组合；子类负责具体键类型、
 * 偏移类型和绑定形式。例如 MySQL 的数值边界直接使用参数标记，PG 的整数边界显式转为 BIGINT。
 * 不提前渲染子表达式，因此参数顺序和表访问仍由 EasyQuery 的原表达式链处理。
 *
 * @param <O> 偏移值类型；同一个 frame 的两侧必须使用这一类型族
 */
public abstract class RangeFrame<O> implements WindowFrame {
    protected final RangeBoundary<? extends O> start;
    protected final RangeBoundary<? extends O> end;
    private final SqlDialect dialect;

    protected RangeFrame(SqlDialect dialect, RangeBoundary<? extends O> start, RangeBoundary<? extends O> end) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
        // 原生规则比较类别而非数值大小：7 PRECEDING 到 8 PRECEDING 可以合法地得到空窗口。
        if (start.kind() == RangeBoundary.Kind.UNBOUNDED_FOLLOWING || end.kind() == RangeBoundary.Kind.UNBOUNDED_PRECEDING
                || start.kind().ordinal() > end.kind().ordinal())
            throw new IllegalArgumentException("Invalid RANGE boundary categories: " + start.kind() + " to " + end.kind());
    }

    @Override
    public void validate(SqlDialect actual, List<WindowOrder> ordering) {
        if (actual != dialect) throw new IllegalArgumentException("This RANGE frame requires " + dialect);
        if (start.hasOffset() || end.hasOffset()) {
            if (ordering.size() != 1)
                throw new IllegalArgumentException("An offset RANGE frame requires exactly one ordering expression");
            validateKey(ordering.get(0).column());
        }
        if (start.hasOffset()) validateOffset(start.offset());
        if (end.hasOffset()) validateOffset(end.offset());
    }

    /**
     * 有有限偏移时才检查键类型；无偏移的 peer frame 不需要数值/时间减法。
     */
    protected abstract void validateKey(PropTypeColumn<?> key);

    /**
     * 校验方言支持的值域，不将月份、自然日或小数距离擅自换算为另一种单位。
     */
    protected abstract void validateOffset(O offset);

    /**
     * 生成单个偏移量的绑定表达式；PRECEDING/FOLLOWING 关键字由共同层添加。
     */
    protected abstract SQLFunction offset(SQLFunc factory, O offset);

    @Override
    public SQLFunction function(SQLFunc factory, SqlDialect actual) {
        return factory.anySQLFunction("RANGE BETWEEN {0} AND {1}", args -> args.sqlFunc(boundary(factory, start)).sqlFunc(boundary(factory, end)));
    }

    private SQLFunction boundary(SQLFunc factory, RangeBoundary<? extends O> boundary) {
        if (boundary.hasOffset())
            return factory.anySQLFunction("{0} " + boundary.kind().name(), args -> args.sqlFunc(offset(factory, boundary.offset())));
        return factory.anySQLFunction(boundary.kind().name().replace('_', ' '), ignored -> {
        });
    }

    /**
     * 保留整数和精确小数两种绑定类别。整数统一为 long，避免 PG 将其误选为 numeric 偏移；
     * BigDecimal 保留原精度。浮点/自定义 Number 不作猜测性截断，超出 BIGINT 的整数明确失败。
     */
    protected static Number numericOffset(Number value) {
        if (value instanceof BigDecimal) {
            BigDecimal decimal = (BigDecimal) value;
            if (decimal.signum() < 0) throw new IllegalArgumentException("A RANGE offset cannot be negative");
            return decimal;
        }
        long integer;
        try {
            if (value instanceof BigInteger) integer = ((BigInteger) value).longValueExact();
            else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                integer = value.longValue();
            else throw new IllegalArgumentException("Use an integral or BigDecimal RANGE offset");
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("An integral RANGE offset must fit a signed BIGINT", overflow);
        }
        if (integer < 0) throw new IllegalArgumentException("A RANGE offset cannot be negative");
        return integer;
    }

    /**
     * 检查可证明的 Java 数值族；Object 载体或未知映射需先通过明确的数据库 CAST 建立键类型。
     */
    protected static Class<?> numericKey(PropTypeColumn<?> key) {
        Class<?> type = EasyClassUtil.getObjectTypeWhenPrimitive(Objects.requireNonNull(key.getPropertyType(), "RANGE key Java type"));
        if (type != Byte.class && type != Short.class && type != Integer.class && type != Long.class
                && type != BigInteger.class && type != BigDecimal.class)
            throw new IllegalArgumentException("Numeric RANGE requires a known integral/decimal key; use an explicit native CAST");
        return type;
    }

    /**
     * 当前时间 RANGE 面向 DATE/无时区时间；Instant、OffsetDateTime 与 DST 语义单独设计。
     */
    protected static void temporalKey(PropTypeColumn<?> key) {
        if (key.getPropertyType() != LocalDate.class && key.getPropertyType() != LocalDateTime.class)
            throw new IllegalArgumentException("Temporal RANGE requires a LocalDate or LocalDateTime ordering key");
    }
}
