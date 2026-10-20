package org.dpdns.zerodep.easyquery.pro.dsl.internal;

/**
 * Unicode checks shared by textual values, literal JSON keys and encoded JSON parameters.
 */
public final class UnicodeStrings {
    private UnicodeStrings() {
    }

    public static String requirePortable(String value, String role) {
        if (value == null) return null;
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            if (unit == 0) throw new IllegalArgumentException(role + " cannot contain NUL");
            if (Character.isHighSurrogate(unit)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException(role + " contains an unpaired high surrogate");
                }
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(role + " contains an unpaired low surrogate");
            }
        }
        return value;
    }
}
