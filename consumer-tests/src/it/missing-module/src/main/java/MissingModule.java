import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;

// Deliberately uncompilable: the independent POM does not select the PostgreSQL module.
final class MissingModule {
    PgSqlDsl unavailable;
}
