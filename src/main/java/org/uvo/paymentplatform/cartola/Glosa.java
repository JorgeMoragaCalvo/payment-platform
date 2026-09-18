package org.uvo.paymentplatform.cartola;

import org.uvo.paymentplatform.support.Rut;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The free-text description of a bank movement.
 *
 * <p>When a Chilean company pays by transfer the payer's RUT is usually somewhere in here —
 * "TRANSF DE 76.543.210-K COMERCIAL ANDES SPA" — but the format is entirely up to the bank and the
 * payer, so extraction is best-effort and always subject to staff confirmation.
 */
public final class Glosa {

    /**
     * Anything RUT-shaped: 7 or 8 body digits, optional thousands dots, optional dash, and a digit
     * or K check digit.
     */
    private static final Pattern RUT_PATTERN =
            Pattern.compile("\\b(\\d{1,2}\\.?\\d{3}\\.?\\d{3})\\s*-?\\s*([\\dkK])\\b");

    private Glosa() {
    }

    /**
     * The first RUT in the glosa whose check digit is valid, normalized.
     *
     * <p>Candidates that fail modulo-11 are <b>skipped rather than returned</b>, so an invoice or
     * order number that happens to look like a RUT cannot poison the strongest match signal there
     * is.
     */
    public static String extractRut(String description) {
        if (description == null) {
            return null;
        }

        Matcher matcher = RUT_PATTERN.matcher(description);

        while (matcher.find()) {
            String candidate = matcher.group(1) + "-" + matcher.group(2);

            if (Rut.isValid(candidate)) {
                return Rut.normalize(candidate);
            }
        }

        return null;
    }
}
