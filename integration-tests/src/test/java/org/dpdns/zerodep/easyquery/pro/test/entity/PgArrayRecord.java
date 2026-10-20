package org.dpdns.zerodep.easyquery.pro.test.entity;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.basic.extension.conversion.ValueConverter;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayTypeHandler;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayValue;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgArrayRecordProxy;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@Table("eqp_pg_array")
@EntityProxy
public class PgArrayRecord implements ProxyEntityAvailable<PgArrayRecord, PgArrayRecordProxy> {
    @Column(primaryKey = true)
    private Integer id;
    @Column(dbType = "INTEGER[]")
    private PgArrayValue<Integer> numbers;
    @Column(dbType = "TEXT[]", typeHandler = PgArrayTypeHandler.class, conversion = LabelsConverter.class)
    private List<String> labels;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public PgArrayValue<Integer> getNumbers() {
        return numbers;
    }

    public void setNumbers(PgArrayValue<Integer> numbers) {
        this.numbers = numbers;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    public static final class LabelsConverter implements ValueConverter<List<String>, PgArrayValue<String>> {
        @Override
        public PgArrayValue<String> serialize(List<String> value, @NotNull ColumnMetadata column) {
            return PgSqlTypes.array(String.class).value(value);
        }

        @Override
        public List<String> deserialize(PgArrayValue<String> value, @NotNull ColumnMetadata column) {
            return value == null ? null : value.elements();
        }
    }
}
