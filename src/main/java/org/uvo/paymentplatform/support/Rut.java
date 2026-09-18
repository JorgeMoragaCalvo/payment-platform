package org.uvo.paymentplatform.support;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chilean RUT handling: normalization, formatting and check-digit validation (modulo 11).
 *
 * <p>Framework-free on purpose, like its counterpart in the source project — it is used by the
 * customer lookup, by the display layer and by the statement importer, and it has to stay trivially
 * unit-testable without a container.
 */
public final class Rut {

    private static final Pattern NON_RUT_CHARS = Pattern.compile("[^0-9kK]");
    private static final Pattern VALID_SHAPE = Pattern.compile("\\d{7,8}[0-9K]");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\d+");

    private Rut() {
    }

    /** Strip dots, dashes and whitespace; uppercase the check digit. "12.345.678-5" → "123456785" */
    public static String normalize(String rut) {
        if (rut == null) {
            return "";
        }
        return NON_RUT_CHARS.matcher(rut).replaceAll("").toUpperCase(Locale.ROOT);
    }

    /**
     * Format a normalized (or raw) RUT as "12.345.678-5", returning the input unchanged when it is
     * too short to format.
     *
     * <p>The grouping separator is forced with {@link Locale#ROOT} and then rewritten to a dot: a
     * locale-sensitive format would already emit dots in some locales and commas in others, and
     * blindly replacing commas would corrupt half of them.
     */
    public static String format(String rut) {
        String normalized = normalize(rut);

        if (normalized.length() < 2) {
            return normalized;
        }

        String dv = normalized.substring(normalized.length() - 1);
        String body = normalized.substring(0, normalized.length() - 1);

        var digits = LEADING_DIGITS.matcher(body);
        if (!digits.find()) {
            return normalized;
        }

        String grouped = String.format(Locale.ROOT, "%,d", Long.parseLong(digits.group()))
                .replace(',', '.');

        return grouped + "-" + dv;
    }

    /** Validate the check digit using the standard modulo-11 algorithm. */
    public static boolean isValid(String rut) {
        String normalized = normalize(rut);

        if (!VALID_SHAPE.matcher(normalized).matches()) {
            return false;
        }

        String dv = normalized.substring(normalized.length() - 1);
        String body = normalized.substring(0, normalized.length() - 1);

        return checkDigit(body).equals(dv);
    }

    /** Compute the check digit for a RUT body (digits only). */
    public static String checkDigit(String body) {
        int sum = 0;
        int factor = 2;

        for (int i = body.length() - 1; i >= 0; i--) {
            int digit = Character.digit(body.charAt(i), 10);
            sum += (digit < 0 ? 0 : digit) * factor;
            factor = factor == 7 ? 2 : factor + 1;
        }

        int rest = 11 - (sum % 11);

        if (rest == 11) {
            return "0";
        }
        if (rest == 10) {
            return "K";
        }
        return String.valueOf(rest);
    }
}
