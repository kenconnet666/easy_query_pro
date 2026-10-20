package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 绑定当前查询的不可变窗口规格，共同维护分区键、排序键和 frame。
 *
 * <p>partitionBy/orderBy 追加到已有列表，rowsBetween 或方言 frame 方法替换当前 frame。
 * 每次操作通过 recreate 返回新规格，应用可以从同一个基础规格派生累计、移动和整组窗口。
 * 列表达式仍属于原查询，不能把规格当作跨 client 的全局缓存。
 * 未设置 frame 时不生成 frame SQL，采用数据库默认规则；RowsWindowSpec 提供 ROWS 配置能力，
 * 并不隐式应用 ROWS。自定义 WindowFrame 也必须不可变，规格不会深复制其内部状态。
 *
 * @param <S> 具体规格类型；避免链式调用后丢失应用或方言新增的方法
 */
public abstract class WindowSpec<S extends WindowSpec<S>> {
    protected final ExpressionScope scope;
    protected final State state;

    /**
     * 只冻结集合结构，不复制列表达式或它们所属的查询上下文。
     */
    protected record State(List<PropTypeColumn<?>> partitions, List<WindowOrder> ordering, WindowFrame frame) {
        protected State {
            partitions = List.copyOf(partitions);
            ordering = List.copyOf(ordering);
        }
    }

    protected WindowSpec(ExpressionScope scope) {
        this(scope, new State(List.of(), List.of(), null));
    }

    protected WindowSpec(ExpressionScope scope, State state) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * 使用相同 scope 和给定状态重建具体 S。子类的额外配置也应在这里传递，不能返回忽略新状态的旧实例。
     */
    protected abstract S recreate(State state);

    /**
     * 追加分区键；不调用此方法表示全局窗口，不人为补一个常量分区。
     */
    public S partitionBy(PropTypeColumn<?>... columns) {
        var partitions = new ArrayList<>(state.partitions());
        for (var column : Objects.requireNonNull(columns, "columns")) {
            scope.requireSource(column);
            partitions.add(column);
        }
        return recreate(new State(partitions, state.ordering(), state.frame()));
    }

    /**
     * 追加升序键；ROWS 如需稳定行序，应由调用方补充能区分并列行的次排序键。
     */
    public S orderBy(PropTypeColumn<?>... columns) {
        var ordering = new ArrayList<>(state.ordering());
        for (var column : Objects.requireNonNull(columns, "columns")) {
            scope.requireSource(column);
            ordering.add(new WindowOrder(column, true));
        }
        return recreate(new State(state.partitions(), ordering, state.frame()));
    }

    public S orderBy(PropTypeColumn<?> column, boolean ascending) {
        scope.requireSource(column);
        var ordering = new ArrayList<>(state.ordering());
        ordering.add(new WindowOrder(column, ascending));
        return recreate(new State(state.partitions(), ordering, state.frame()));
    }

    /**
     * 采用三库共同 ROWS 边界规则；即使方向合法，靠近数据边缘时仍可能得到空 frame。
     */
    public S rowsBetween(RowBoundary start, RowBoundary end) {
        return frame(new RowsFrame(start, end));
    }

    /**
     * 方言只需提供自己的 frame，继续复用共同分区/排序与 SELF 创建链。
     */
    protected S frame(WindowFrame frame) {
        return recreate(new State(state.partitions(), state.ordering(), Objects.requireNonNull(frame, "frame")));
    }

    void validate(ExpressionScope target, boolean requiresOrdering) {
        // 放在挂接函数时校验，允许调用方先指定 frame 再补充排序，不约束链式方法的书写顺序。
        if (target.runtime() != scope.runtime())
            throw new IllegalArgumentException("A window cannot use another client's specification");
        for (var column : state.partitions()) validateKey(column);
        for (var order : state.ordering()) validateKey(order.column());
        if (requiresOrdering && state.ordering().isEmpty())
            throw new IllegalArgumentException("This window function requires an explicit ordering");
        if (state.frame() != null) state.frame().validate(scope.dialect(), state.ordering());
    }

    private void validateKey(PropTypeColumn<?> column) {
        if (!ExpressionScope.isRowLocal(scope.operand(column, scope.runtime().fx())))
            throw new IllegalArgumentException("Project aggregates, windows and unclassified expressions before using them as window keys");
    }

    SQLFunction apply(SQLFunc factory, SQLFunction input) {
        // 子函数保持结构化节点直到最终渲染，不能先生成 SQL 再拼接，避免参数和表别名脱离上下文。
        var arguments = new ArrayList<SQLFunction>();
        arguments.add(input);
        var template = new StringBuilder("{0} OVER (");
        if (!state.partitions().isEmpty()) {
            template.append("PARTITION BY ");
            for (int i = 0; i < state.partitions().size(); i++) {
                if (i != 0) template.append(',');
                append(template, arguments, scope.operand(state.partitions().get(i), factory));
            }
        }
        if (!state.ordering().isEmpty()) {
            if (!state.partitions().isEmpty()) template.append(' ');
            template.append("ORDER BY ");
            for (int i = 0; i < state.ordering().size(); i++) {
                if (i != 0) template.append(',');
                var order = state.ordering().get(i);
                append(template, arguments, scope.operand(order.column(), factory));
                template.append(order.ascending() ? " ASC" : " DESC");
            }
        }
        if (state.frame() != null) {
            template.append(' ');
            append(template, arguments, state.frame().function(factory, scope.dialect()));
        }
        template.append(')');
        return factory.anySQLFunction(template.toString(), sql -> arguments.forEach(sql::sqlFunc));
    }

    private static void append(StringBuilder template, List<SQLFunction> arguments, SQLFunction argument) {
        template.append('{').append(arguments.size()).append('}');
        arguments.add(argument);
    }
}
