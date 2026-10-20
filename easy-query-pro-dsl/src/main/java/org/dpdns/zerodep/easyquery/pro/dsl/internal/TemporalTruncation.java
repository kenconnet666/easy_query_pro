package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import org.dpdns.zerodep.easyquery.pro.dsl.TruncationUnit;

/**
 * Trusted database templates; the shared DSL binds their source through the upstream SQLFunction API.
 */
public interface TemporalTruncation {
    String template(TruncationUnit unit, boolean dateOnly);
}
