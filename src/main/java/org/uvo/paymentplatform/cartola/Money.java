package org.uvo.paymentplatform.cartola;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amounts as Chilean bank exports write them.
 *
 * <p>Accepted shapes: "1.234.567", "$ 1.234.567", "1.234.567,00", "1,234,567.00" (an export in US
 * locale), "-1.234" and "(1.234)".
 *
 * <p><b>A lone dot is always read as a thousands separator</b>: the peso has no subunit in practice,
 * so "1.234" in a statement is one thousand two hundred thirty four pesos, never 1,23. Cents only
 * appear when the export also uses a comma, and they are rounded away.
 */
public final class Money {

    private static final Pattern ACCOUNTING_NEGATIVE = Pattern.compile("^\\((.*)\\)$");
    private static final Pattern NON_NUMERIC = Pattern.compile("[^0-9,.\\-]");
    private static final Pattern CENTS_COMMA = Pattern.compile(",\\d{2}$");
    private static final Pattern NUMERIC = Pattern.compile("\\d+(\\.\\d+)?");

    private Money() {
    }

    /** @return the amount in pesos, or null when the cell holds no number at all. */
    public static Integer parseClp(String raw) {
        String value = raw == null ? "" : raw.trim();

        if (value.isEmpty()) {
            return null;
        }

        boolean negative = false;

        Matcher accounting = ACCOUNTING_NEGATIVE.matcher(value);
        if (accounting.matches()) {
            negative = true;
            value = accounting.group(1);
        }

        // Drop currency symbols, thin spaces and anything else that is not part of the number.
        value = NON_NUMERIC.matcher(value).replaceAll("");

        if (value.isEmpty() || value.equals("-")) {
            return null;
        }

        if (value.startsWith("-")) {
            negative = true;
        }

        value = value.replace("-", "");

        int lastDot = value.lastIndexOf('.');
        int lastComma = value.lastIndexOf(',');

        if (lastDot >= 0 && lastComma >= 0) {
            // Both present: whichever comes last is the decimal mark.
            char decimalMark = lastDot > lastComma ? '.' : ',';
            char thousandsMark = decimalMark == '.' ? ',' : '.';
            value = value.replace(String.valueOf(thousandsMark), "");
            value = value.replace(decimalMark, '.');
        } else if (lastComma >= 0) {
            // A single comma with two trailing digits is cents; anything else is a thousands mark.
            value = CENTS_COMMA.matcher(value).find()
                    ? value.replace(',', '.')
                    : value.replace(",", "");
        } else {
            // Dots only — thousands separators, per the note above.
            value = value.replace(".", "");
        }

        if (!NUMERIC.matcher(value).matches()) {
            return null;
        }

        // BigDecimal rather than a double: these are money, and HALF_UP is what the source
        // project's round() does.
        int amount = new BigDecimal(value).setScale(0, RoundingMode.HALF_UP).intValueExact();

        return negative ? -amount : amount;
    }
}
