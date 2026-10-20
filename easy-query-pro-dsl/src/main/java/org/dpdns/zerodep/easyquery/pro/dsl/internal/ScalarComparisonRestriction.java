package org.dpdns.zerodep.easyquery.pro.dsl.internal;

/**
 * A typed value can require an explicit conversion before entering common scalar comparison operations.
 */
public interface ScalarComparisonRestriction {
    String scalarComparisonError();
}
