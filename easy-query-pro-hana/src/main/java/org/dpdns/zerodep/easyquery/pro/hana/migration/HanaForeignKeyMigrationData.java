package org.dpdns.zerodep.easyquery.pro.hana.migration;

import com.easy.query.core.migration.data.ForeignKeyMigrationData;

/**
 * 上游没有目标 schema 字段；单独传递它，避免解析含点号的表名。null 表示连接的当前 schema。
 */
public final class HanaForeignKeyMigrationData extends ForeignKeyMigrationData {
    private String targetSchema;

    public String getTargetSchema() {
        return targetSchema;
    }

    public void setTargetSchema(String targetSchema) {
        this.targetSchema = targetSchema;
    }
}
