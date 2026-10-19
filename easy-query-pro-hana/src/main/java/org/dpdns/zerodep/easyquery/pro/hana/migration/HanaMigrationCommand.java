package org.dpdns.zerodep.easyquery.pro.hana.migration;

import com.easy.query.core.migration.MigrationCommand;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 保留语句边界，注释或默认值中的分号不能用于拆分 SQL。
 */
record HanaMigrationCommand(List<String> statements) implements MigrationCommand {
    HanaMigrationCommand {
        statements = List.copyOf(statements);
    }

    HanaMigrationCommand(String statement) {
        this(List.of(statement));
    }

    @Override
    public String toSQL() {
        return statements.stream().map(sql -> sql + ";").collect(Collectors.joining(System.lineSeparator()));
    }
}
