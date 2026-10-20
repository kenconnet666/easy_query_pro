package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.basic.jdbc.executor.ResultColumnMetadata;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.SQLSelectAsExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.AnyTypeExpressionImpl;
import com.easy.query.core.util.EasyClassUtil;

import java.util.Objects;
import java.util.function.Function;

/**
 * 表示保持整列业务映射的 SQL 运算，例如 JSON 文档修改、数组和 interval 组合。
 *
 * <p>T 始终是调用方看到的业务值类型。存在 reader 时，运行时使用 Object.class 作为上游标量载体，
 * 避免把 record/List 等值重新当作实体构造；这不会把公共泛型返回值退化为 Object。
 * Draft 通过 as(alias) 注册读取规则，selectColumn 通过 ResultColumnMetadata 读取，
 * 两条路径必须使用同一个最终 reader；普通 DTO 的目标映射仍由投影工厂保留。
 */
public abstract class MappedValueExpression<T> extends AnyTypeExpressionImpl<T> {
    protected final ExpressionScope scope;
    private final ColumnReader reader;

    /**
     * @param scope    当前查询作用域
     * @param source   上一段表达式的映射来源；重建时不能回退到最初实体列
     * @param function 纯 SQL 构造回调，可能在读取规则识别和 SQL 构造阶段多次求值，不应执行 JDBC
     */
    protected MappedValueExpression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function) {
        this(scope, source, function, ColumnReaders.source(source));
    }

    private MappedValueExpression(ExpressionScope scope, PropTypeColumn<T> source, Function<SQLFunc, SQLFunction> function, ColumnReader reader) {
        super(scope.context(), source.getTable(), reader == null ? null : source.getValue(),
                factory -> {
                    var value = function.apply(factory);
                    return scope.projection(value, source.getTable());
                },
                reader != null ? Object.class : EasyClassUtil.getObjectTypeWhenPrimitive(Objects.requireNonNull(source.getPropertyType(), "source Java type")));
        // Draft 可能已经暴露具体业务类，必须主动维持标量读取入口，不能依赖它仍是 Object。
        this.scope = scope;
        this.reader = reader;
    }

    /**
     * 以最终表达式语义选择可空读取：最初实体字段是 primitive，追加窗口后也可能得到 NULL。
     */
    final ColumnReader columnReader() {
        if (reader == null) return null;
        var expression = func().apply(scope.runtime().fx());
        return ExpressionScope.preservesRows(expression) && !ExpressionScope.isRowLocal(expression) ? ColumnReaders.nullable(reader) : reader;
    }

    @Override
    public ResultColumnMetadata getTableResultColumnMetadataOrNull(int index) {
        var resultReader = columnReader();
        return resultReader == null ? super.getTableResultColumnMetadataOrNull(index) : ColumnReaders.scalarMetadata(index, resultReader);
    }

    @Override
    public SQLSelectAsExpression as(String alias) {
        var resultReader = columnReader();
        if (resultReader != null) {
            // Draft 不会自动从计算表达式继承完整列映射，因此在公开 reader SPI 中登记。
            String key = alias == null ? getValue() : alias;
            ColumnReaders.setAutomatic(scope.context().getEntityExpressionBuilder(), key, resultReader);
        }
        return super.as(alias);
    }
}
