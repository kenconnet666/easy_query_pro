package org.dpdns.zerodep.easyquery.pro.test;

final class TestEnvironment {
    private TestEnvironment() {
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test environment variable is missing: " + name);
        }
        return value;
    }
}
