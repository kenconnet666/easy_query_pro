package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.util.EasyStringUtil;

final class TestEnvironment {
    private TestEnvironment() {
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || EasyStringUtil.isBlank(value)) {
            throw new IllegalStateException("Required test environment variable is missing: " + name);
        }
        return value;
    }
}
