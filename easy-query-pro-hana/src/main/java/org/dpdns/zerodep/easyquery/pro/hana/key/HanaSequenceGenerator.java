package org.dpdns.zerodep.easyquery.pro.hana.key;

import com.easy.query.core.basic.extension.conversion.SQLPropertyConverter;
import com.easy.query.core.basic.extension.generated.GeneratedKeySQLColumnGenerator;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.metadata.ColumnMetadata;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;

/** Subclass per sequence, then register the instance using EasyQuery's existing generator registry. */
public abstract class HanaSequenceGenerator implements GeneratedKeySQLColumnGenerator {
    private final String qualifiedName;

    protected HanaSequenceGenerator(String sequence) {
        this(null, sequence);
    }

    protected HanaSequenceGenerator(String schema, String sequence) {
        var keyword = new HanaSQLKeyword();
        qualifiedName = (schema == null ? "" : keyword.getQuoteName(schema) + ".") + keyword.getQuoteName(sequence);
    }

    @Override
    public final void configure(TableAvailable table, ColumnMetadata column, SQLPropertyConverter converter,
                                QueryRuntimeContext runtime) {
        if (!(runtime.getQueryConfiguration().getDialect() instanceof HanaSQLKeyword)) {
            throw new IllegalArgumentException("HanaSequenceGenerator requires HanaDatabaseConfiguration");
        }
        converter.sqlNativeSegment(qualifiedName + ".NEXTVAL");
    }

    public final String currentValueExpression() {
        return qualifiedName + ".CURRVAL";
    }
}
