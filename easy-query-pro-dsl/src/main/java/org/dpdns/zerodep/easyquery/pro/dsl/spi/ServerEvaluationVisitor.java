package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.expression.AnonymousEntityTableSQLExpression;
import com.easy.query.core.expression.sql.expression.AnonymousUnionEntityQuerySQLExpression;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.visitor.TableVisitor;
import com.easy.query.core.util.EasySQLSegmentUtil;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import com.easy.query.core.expression.sql.builder.ExpressionBuilder;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

/**
 * 最终分片查询的语义检查；每次构造 SQL 时重新遍历，避免把已删除的窗口留成 context 中的永久标记。
 *
 * <p>不同于只识别当前投影层的 SemanticVisitor，本检查必须进入子查询和声明的 CTE。
 * 仅拒绝 Pro 已标记的服务器运算，不猜测任意原生 SQL 或第三方函数的计算语义。
 */
final class ServerEvaluationVisitor implements TableVisitor {
    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

    static void validate(EntityQueryExpressionBuilder query, EntityQuerySQLExpression rendered) {
        if (!query.getExpressionContext().isSharding()) return;
        ServerEvaluationVisitor visitor = new ServerEvaluationVisitor();
        query.accept(visitor);
        // 上游按引用关系裁剪导航 JOIN；只检查实际输出的关系和 ON，不扫描未使用的 relationTables。
        visitor.rendered(rendered);
    }

    void query(EntityQueryExpressionBuilder query, Runnable contents) {
        if (!visited.add(query)) return;
        contents.run();
        for (ExpressionBuilder declaration : query.getExpressionContext().getDeclareExpressions()) {
            if (declaration instanceof EntityQueryExpressionBuilder) ((EntityQueryExpressionBuilder) declaration).accept(this);
        }
    }

    private void rendered(EntityQuerySQLExpression query) {
        if (!visited.add(query)) return;
        EasySQLSegmentUtil.tableVisit(query.getProjects(), this);
        EasySQLSegmentUtil.tableVisit(query.getWhere(), this);
        EasySQLSegmentUtil.tableVisit(query.getGroup(), this);
        EasySQLSegmentUtil.tableVisit(query.getHaving(), this);
        EasySQLSegmentUtil.tableVisit(query.getOrder(), this);
        if (query instanceof AnonymousUnionEntityQuerySQLExpression)
            ((AnonymousUnionEntityQuerySQLExpression) query).getEntityQuerySQLExpressions().forEach(this::rendered);
        if (query.getTables() != null) {
            for (EntityTableSQLExpression table : query.getTables()) {
                EasySQLSegmentUtil.tableVisit(table.getOn(), this);
                if (table instanceof AnonymousEntityTableSQLExpression)
                    rendered(((AnonymousEntityTableSQLExpression) table).getEntityQuerySQLExpression());
            }
        }
    }

    static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Pro window, aggregate or unclassified operations require server-side evaluation before shard merging");
    }

    @Override
    public Set<TableAvailable> getTables() {
        return ImmutableCollections.setOf();
    }

    @Override
    public void visit(TableAvailable table) {
    }

    @Override
    public boolean containsTable(TableAvailable table) {
        return false;
    }

    @Override
    public void addAll(TableVisitor other) {
    }
}
