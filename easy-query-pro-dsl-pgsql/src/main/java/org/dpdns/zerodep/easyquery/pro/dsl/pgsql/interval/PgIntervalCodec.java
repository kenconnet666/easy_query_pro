package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.nio.ByteBuffer;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Decodes the four server output formats, not PostgreSQL's arbitrary human-readable input language.
 */
final class PgIntervalCodec {
    private static final BigInteger MICROS_PER_SECOND = BigInteger.valueOf(1_000_000);
    private static final Pattern UNIT = Pattern.compile("\\G\\s*([+-]?\\d+(?:\\.\\d+)?)\\s+(years?|mons?|days?|hours?|mins?|secs?)\\b");
    private static final Pattern ISO_DATE = Pattern.compile("\\G([+-]?\\d+)([YMD])");
    private static final Pattern ISO_TIME = Pattern.compile("\\G([+-]?\\d+(?:\\.\\d+)?)([HMS])");
    private static final Pattern CLOCK = Pattern.compile("([+-]?)(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d{1,6})?)");
    private static final Pattern YEAR_MONTH = Pattern.compile("([+-]?)(\\d+)-(\\d+)");

    private PgIntervalCodec() {
    }

    static String encode(PgInterval value) {
        if (value == PgInterval.Infinity.POSITIVE) return "infinity";
        if (value == PgInterval.Infinity.NEGATIVE) return "-infinity";
        PgInterval.Finite finite = (PgInterval.Finite) value;
        // Explicit signs prevent sql_standard from propagating a leading minus to later positive fields.
        return signed(finite.months()) + " months " + signed(finite.days()) + " days " + signed(finite.microseconds()) + " microseconds";
    }

    private static String signed(long value) {
        return value < 0 ? Long.toString(value) : "+" + value;
    }

    static PgInterval decodeBinary(byte[] bytes) throws SQLException {
        // PostgreSQL sends time (int64), days (int32), then months (int32), in network byte order.
        if (bytes == null || bytes.length != Long.BYTES + 2 * Integer.BYTES)
            throw new SQLException("Invalid PostgreSQL binary interval length");
        ByteBuffer value = ByteBuffer.wrap(bytes);
        long micros = value.getLong();
        int days = value.getInt();
        int months = value.getInt();
        if (micros == Long.MAX_VALUE && days == Integer.MAX_VALUE && months == Integer.MAX_VALUE)
            return PgInterval.Infinity.POSITIVE;
        if (micros == Long.MIN_VALUE && days == Integer.MIN_VALUE && months == Integer.MIN_VALUE)
            return PgInterval.Infinity.NEGATIVE;
        return PgInterval.of(months, days, micros);
    }

    static PgInterval decode(String text) throws SQLException {
        if (text == null) return null;
        if (text.equals("infinity")) return PgInterval.Infinity.POSITIVE;
        if (text.equals("-infinity")) return PgInterval.Infinity.NEGATIVE;
        try {
            PgIntervalCodec.Parts parts = new Parts();
            if (text.startsWith("P")) parseIso(text, parts);
            else if (text.startsWith("@") || text.chars().anyMatch(Character::isLetter)) parseUnits(text, parts);
            else parseSqlStandard(text, parts);
            return PgInterval.of(parts.months.intValueExact(), parts.days.intValueExact(), parts.micros.longValueExact());
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new SQLException("Cannot decode PostgreSQL interval output: " + text, failure);
        }
    }

    private static void parseIso(String text, Parts parts) {
        String[] sections = text.substring(1).split("T", -1);
        if (sections.length > 2) throw new IllegalArgumentException("Invalid ISO interval sections");
        scanIso(sections[0], ISO_DATE, parts, false);
        if (sections.length == 2) scanIso(sections[1], ISO_TIME, parts, true);
        if (text.equals("P") || text.equals("PT")) throw new IllegalArgumentException("Empty ISO interval");
    }

    private static void scanIso(String text, Pattern pattern, Parts parts, boolean time) {
        Matcher matcher = pattern.matcher(text);
        int end = 0;
        while (matcher.find()) {
            String unit;
            switch (matcher.group(2)) {
                case "Y": unit = "year"; break;
                case "M": unit = time ? "min" : "mon"; break;
                case "D": unit = "day"; break;
                case "H": unit = "hour"; break;
                case "S": unit = "sec"; break;
                default: throw new IllegalArgumentException("Unknown interval unit");
            }
            parts.add(matcher.group(1), unit);
            end = matcher.end();
        }
        if (end != text.length()) throw new IllegalArgumentException("Invalid ISO interval component");
    }

    private static void parseUnits(String text, Parts parts) {
        String body = text.startsWith("@") ? text.substring(1).trim() : text;
        boolean ago = body.endsWith(" ago");
        if (ago) body = body.substring(0, body.length() - 4);
        if (body.equals("0")) return;
        Matcher matcher = UNIT.matcher(body);
        int end = 0;
        while (matcher.find()) {
            parts.add(matcher.group(1), matcher.group(2));
            end = matcher.end();
        }
        String tail = body.substring(end).trim();
        if (!tail.isEmpty()) parts.micros = parts.micros.add(clock(tail));
        if (end == 0 && tail.isEmpty()) throw new IllegalArgumentException("Empty interval");
        if (ago) {
            parts.months = parts.months.negate();
            parts.days = parts.days.negate();
            parts.micros = parts.micros.negate();
        }
    }

    private static void parseSqlStandard(String text, Parts parts) {
        String[] tokens = text.trim().split("\\s+");
        if (tokens.length > 3) throw new IllegalArgumentException("Invalid SQL interval fields");
        boolean leadingNegative = tokens[0].startsWith("-");
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].startsWith("+") || tokens[i].startsWith("-")) {
                leadingNegative = false;
                break;
            }
        }
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            Matcher yearMonth = YEAR_MONTH.matcher(token);
            boolean inheritMinus = i > 0 && leadingNegative;
            if (yearMonth.matches()) {
                BigInteger value = new BigInteger(yearMonth.group(2)).multiply(BigInteger.valueOf(12)).add(new BigInteger(yearMonth.group(3)));
                parts.months = yearMonth.group(1).equals("-") ? value.negate() : value;
            } else if (token.contains(":")) {
                BigInteger value = clock(token);
                parts.micros = inheritMinus ? value.negate() : value;
            } else {
                BigInteger value = new BigInteger(token);
                parts.days = inheritMinus ? value.negate() : value;
            }
        }
    }

    private static BigInteger clock(String text) {
        Matcher match = CLOCK.matcher(text);
        if (!match.matches()) throw new IllegalArgumentException("Invalid interval time component");
        BigDecimal seconds = new BigDecimal(match.group(2)).multiply(BigDecimal.valueOf(3600))
                .add(new BigDecimal(match.group(3)).multiply(BigDecimal.valueOf(60))).add(new BigDecimal(match.group(4)));
        BigInteger value = seconds.multiply(new BigDecimal(MICROS_PER_SECOND)).toBigIntegerExact();
        return match.group(1).equals("-") ? value.negate() : value;
    }

    private static final class Parts {
        private BigInteger months = BigInteger.ZERO;
        private BigInteger days = BigInteger.ZERO;
        private BigInteger micros = BigInteger.ZERO;

        private void add(String encoded, String unit) {
            BigDecimal value = new BigDecimal(encoded);
            if (unit.startsWith("year"))
                months = months.add(value.multiply(BigDecimal.valueOf(12)).toBigIntegerExact());
            else if (unit.startsWith("mon")) months = months.add(value.toBigIntegerExact());
            else if (unit.startsWith("day")) days = days.add(value.toBigIntegerExact());
            else {
                long seconds = unit.startsWith("hour") ? 3600 : unit.startsWith("min") ? 60 : 1;
                micros = micros.add(value.multiply(BigDecimal.valueOf(seconds * 1_000_000)).toBigIntegerExact());
            }
        }
    }
}
