package org.dpdns.zerodep.easyquery.pro.hana.config;

import java.util.Objects;
import java.util.regex.Pattern;

/** The major, minor and revision components of a HANA server version. */
public record HanaVersion(int major, int minor, int revision) implements Comparable<HanaVersion> {
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.\\d+)*");

    public HanaVersion {
        if (major < 0 || minor < 0 || revision < 0) {
            throw new IllegalArgumentException("HANA version components must be nonnegative");
        }
    }

    public static HanaVersion parse(String value) {
        var matcher = VERSION.matcher(Objects.requireNonNull(value, "version"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unrecognized HANA version: " + value);
        }
        return new HanaVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    @Override
    public int compareTo(HanaVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(revision, other.revision);
        return result;
    }
}
