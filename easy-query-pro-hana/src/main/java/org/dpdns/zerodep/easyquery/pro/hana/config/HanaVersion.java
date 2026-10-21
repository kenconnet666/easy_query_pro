package org.dpdns.zerodep.easyquery.pro.hana.config;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** The major, minor and revision components of a HANA server version. */
public final class HanaVersion implements Comparable<HanaVersion> {
    private final int major;
    private final int minor;
    private final int revision;

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.\\d+)*");

    public HanaVersion(int major, int minor, int revision) {
        if (major < 0 || minor < 0 || revision < 0) {
            throw new IllegalArgumentException("HANA version components must be nonnegative");
        }
        this.major = major;
        this.minor = minor;
        this.revision = revision;
    }

    public static HanaVersion parse(String value) {
        Matcher matcher = VERSION.matcher(Objects.requireNonNull(value, "version"));
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

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int revision() {
        return revision;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HanaVersion)) return false;
        HanaVersion other = (HanaVersion) o;
        return major == other.major
                && minor == other.minor
                && revision == other.revision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, revision);
    }

    @Override
    public String toString() {
        return "HanaVersion[major=" + major + ", minor=" + minor + ", revision=" + revision + "]";
    }
}
