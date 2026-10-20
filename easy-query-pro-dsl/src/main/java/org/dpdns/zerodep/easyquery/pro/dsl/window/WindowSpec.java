package org.dpdns.zerodep.easyquery.pro.dsl.window;

import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.proxy.PropTypeColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ExpressionScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, query-bound window specification. Subclasses rebuild their concrete type without a self cast.
 */
public abstract class WindowSpec<S extends WindowSpec<S>> {
    protected final ExpressionScope scope;
    protected final State state;

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

    protected abstract S recreate(State state);

    public S partitionBy(PropTypeColumn<?>... columns) {
        var partitions = new ArrayList<>(state.partitions());
        for (var column : Objects.requireNonNull(columns, "columns")) {
            scope.requireSource(column);
            partitions.add(column);
        }
        return recreate(new State(partitions, state.ordering(), state.frame()));
    }

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

    public S rowsBetween(RowBoundary start, RowBoundary end) {
        return frame(new RowsFrame(start, end));
    }

    protected S frame(WindowFrame frame) {
        return recreate(new State(state.partitions(), state.ordering(), Objects.requireNonNull(frame, "frame")));
    }

    void validate(ExpressionScope target, boolean valueWindow) {
        if (target.runtime() != scope.runtime())
            throw new IllegalArgumentException("A window cannot use another client's specification");
        for (var column : state.partitions()) validateKey(column);
        for (var order : state.ordering()) validateKey(order.column());
        if (valueWindow && state.ordering().isEmpty())
            throw new IllegalArgumentException("Value windows require an explicit ordering");
        if (state.frame() != null) state.frame().validate(scope.dialect(), state.ordering());
    }

    private void validateKey(PropTypeColumn<?> column) {
        if (!ExpressionScope.isRowLocal(scope.operand(column, scope.runtime().fx())))
            throw new IllegalArgumentException("Project aggregates, windows and unclassified expressions before using them as window keys");
    }

    SQLFunction apply(SQLFunc factory, SQLFunction input) {
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
