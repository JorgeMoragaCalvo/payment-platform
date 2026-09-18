package org.uvo.paymentplatform.cartola;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

/**
 * Dates as Chilean bank exports write them: day first, either separator, sometimes with a time
 * appended, sometimes ISO because the export went through a spreadsheet first.
 */
public final class Dates {

    /**
     * Tried in order. Day-first formats come before the ISO one because "01/02/2026" is 1 February
     * in Chile, never 2 January.
     *
     * <p>Note {@code uuuu}, not {@code yyyy}: the parsing below is STRICT, and a strict
     * {@code yyyy} is year-of-era, which then demands an era in the input and rejects every date.
     */
    private static final List<DateTimeFormatter> FORMATS = List.of(
            strict("dd/MM/uuuu"),
            strict("dd-MM-uuuu"),
            strict("dd/MM/uu"),
            strict("dd-MM-uu"),
            strict("uuuu-MM-dd"),
            strict("uuuu/MM/dd"));

    private Dates() {
    }

    public static LocalDate parse(String raw) {
        String value = raw == null ? "" : raw.trim();

        if (value.isEmpty()) {
            return null;
        }

        // Drop a trailing time component: "12/03/2026 14:05:00".
        value = value.split("\\s+")[0];

        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // Try the next layout.
            }
        }

        return null;
    }

    /**
     * STRICT resolution is what rejects a rolled-over date. A lenient parser turns 32/01/2026 into
     * 01/02/2026, which would silently post a movement on the wrong day; the source project guards
     * against the same thing by requiring the parsed date to format back to the original text.
     */
    private static DateTimeFormatter strict(String pattern) {
        return new DateTimeFormatterBuilder()
                .appendPattern(pattern)
                .toFormatter(Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT);
    }
}
