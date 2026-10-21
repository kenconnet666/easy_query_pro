package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import com.easy.query.core.proxy.SQLColumn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ImmutableCollections;

/**
 * 邻接树的不可变构建规格。节点键与父键必须具有相同的 Java 类型，并在数据库中具有相同 SQL 类型。
 * 来源 where 决定参与构树的边，roots 只选择遍历起点；结果查询上的 where 则仅筛选已计算的行。
 *
 * <p>默认拒绝重复节点和循环，孤儿按 HANA 默认规则忽略。允许多父节点时同一业务节点可以出现多次，
 * 应以 HIERARCHY_RANK 标识一次遍历中的节点，并为兄弟提供能稳定区分边的排序列。
 *
 * @param <K> 节点键类型，同时约束根集合和后续导航输入
 */
public final class HanaHierarchySpec<K> {
    public enum Orphans {IGNORE, ROOT, ERROR}

    public enum Cycles {ERROR, BREAKUP}

    public enum Multiparent {ERROR, ALLOW}

    private final SQLColumn<?, K> node;
    private final SQLColumn<?, K> parent;
    private final List<Sibling> siblings;
    private final List<K> roots;
    private final Integer depth;
    private final Orphans orphans;
    private final Cycles cycles;
    private final Multiparent multiparent;

    private HanaHierarchySpec(SQLColumn<?, K> node, SQLColumn<?, K> parent, List<Sibling> siblings,
                              List<K> roots, Integer depth, Orphans orphans, Cycles cycles, Multiparent multiparent) {
        this.node = Objects.requireNonNull(node, "node");
        this.parent = Objects.requireNonNull(parent, "parent");
        this.siblings = ImmutableCollections.listCopyOf(siblings);
        this.roots = roots == null ? null : ImmutableCollections.listCopyOf(roots);
        this.depth = depth;
        this.orphans = Objects.requireNonNull(orphans, "orphans");
        this.cycles = Objects.requireNonNull(cycles, "cycles");
        this.multiparent = Objects.requireNonNull(multiparent, "multiparent");
        if (depth != null && depth < 0) throw new IllegalArgumentException("Hierarchy depth must be nonnegative");
        if (depth != null && orphans != Orphans.IGNORE)
            throw new IllegalArgumentException("HANA hierarchy depth requires the IGNORE orphan policy");
        if (roots != null && roots.isEmpty())
            throw new IllegalArgumentException("Explicit hierarchy roots must not be empty");
    }

    public static <K> HanaHierarchySpec<K> of(SQLColumn<?, K> node, SQLColumn<?, K> parent) {
        return new HanaHierarchySpec<>(node, parent, ImmutableCollections.listOf(), null, null, Orphans.IGNORE, Cycles.ERROR, Multiparent.ERROR);
    }

    /**
     * 未指定 roots 时以父键为 SQL NULL 的记录为根；不会把 0、空字符串等业务哨兵改为 NULL。
     */
    public HanaHierarchySpec<K> roots(Collection<? extends K> values) {
        Objects.requireNonNull(values, "roots");
        if (values.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Explicit hierarchy roots cannot contain SQL NULL");
        return new HanaHierarchySpec<>(node, parent, siblings, ImmutableCollections.listCopyOf(values), depth, orphans, cycles, multiparent);
    }

    /**
     * 构建时的最大边数，根的深度为 0；生成的 HIERARCHY_LEVEL 则从 1 开始。
     */
    public HanaHierarchySpec<K> depth(int maximum) {
        return new HanaHierarchySpec<>(node, parent, siblings, roots, maximum, orphans, cycles, multiparent);
    }

    public HanaHierarchySpec<K> orphans(Orphans policy) {
        return new HanaHierarchySpec<>(node, parent, siblings, roots, depth, policy, cycles, multiparent);
    }

    /**
     * BREAKUP 仍可能产生重复节点；需要同时允许多父节点才能消费这些循环闭合行。
     */
    public HanaHierarchySpec<K> cycles(Cycles policy) {
        return new HanaHierarchySpec<>(node, parent, siblings, roots, depth, orphans, policy, multiparent);
    }

    public HanaHierarchySpec<K> multiparent(Multiparent policy) {
        return new HanaHierarchySpec<>(node, parent, siblings, roots, depth, orphans, cycles, policy);
    }

    /**
     * 只接受来源当前暴露的映射列，计算排序值应先投影到一个具名 DTO 列。
     */
    public HanaHierarchySpec<K> siblingsAsc(SQLColumn<?, ?> column) {
        return sibling(column, true);
    }

    public HanaHierarchySpec<K> siblingsDesc(SQLColumn<?, ?> column) {
        return sibling(column, false);
    }

    private HanaHierarchySpec<K> sibling(SQLColumn<?, ?> column, boolean ascending) {
        ArrayList<HanaHierarchySpec.Sibling> copy = new ArrayList<>(siblings);
        copy.add(new Sibling(Objects.requireNonNull(column, "column"), ascending));
        return new HanaHierarchySpec<>(node, parent, copy, roots, depth, orphans, cycles, multiparent);
    }

    SQLColumn<?, K> node() {
        return node;
    }

    SQLColumn<?, K> parent() {
        return parent;
    }

    List<Sibling> siblings() {
        return siblings;
    }

    List<K> roots() {
        return roots;
    }

    Integer depth() {
        return depth;
    }

    Orphans orphans() {
        return orphans;
    }

    Cycles cycles() {
        return cycles;
    }

    Multiparent multiparent() {
        return multiparent;
    }

    static final class Sibling {
        private final SQLColumn<?, ?> column;
        private final boolean ascending;

        Sibling(SQLColumn<?, ?> column, boolean ascending) {
            this.column = column;
            this.ascending = ascending;
        }

        public SQLColumn<?, ?> column() {
            return column;
        }

        public boolean ascending() {
            return ascending;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Sibling)) return false;
            Sibling other = (Sibling) o;
            return Objects.equals(column, other.column)
                    && ascending == other.ascending;
        }

        @Override
        public int hashCode() {
            return Objects.hash(column, ascending);
        }

        @Override
        public String toString() {
            return "Sibling[column=" + column + ", ascending=" + ascending + "]";
        }
    }
}
