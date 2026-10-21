package org.dpdns.zerodep.easyquery.pro.hana.migration;

import com.easy.query.core.migration.MigrationCommand;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Objects;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;

/**
 * 保留语句边界，注释或默认值中的分号不能用于拆分 SQL。
 */
final class HanaMigrationCommand implements MigrationCommand {
    private final List<String> statements;

    HanaMigrationCommand(List<String> statements) {
        statements = ImmutableCollections.listCopyOf(statements);
        this.statements = statements;
    }

    HanaMigrationCommand(String statement) {
        this(ImmutableCollections.listOf(statement));
    }

    @Override
    public String toSQL() {
        return statements.stream().map(sql -> sql + ";").collect(Collectors.joining(System.lineSeparator()));
    }

    public List<String> statements() {
        return statements;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HanaMigrationCommand)) return false;
        HanaMigrationCommand other = (HanaMigrationCommand) o;
        return Objects.equals(statements, other.statements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(statements);
    }

    @Override
    public String toString() {
        return "HanaMigrationCommand[statements=" + statements + "]";
    }
}
