package org.uvo.paymentplatform.support;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Accent- and case-insensitive text handling, shared by the statement importer (matching header
 * labels like "Descripción" against a configured "descripcion") and by the match engine (comparing
 * a bank glosa against a company name).
 *
 * <p>Chilean bank exports are inconsistent about accents and character encoding, so nothing may
 * depend on them being present or correct.
 */
public final class Text {

    /**
     * Chilean company-form suffixes and filler words. Every second company is a "SpA", so matching
     * on one means nothing.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "spa", "ltda", "limitada", "sa", "eirl", "y", "de", "del", "la", "el", "los", "las");

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private static final int MIN_TOKEN_LENGTH = 3;

    private Text() {
    }

    /**
     * Lowercased, unaccented, with runs of non-alphanumerics collapsed to single spaces:
     * "Transf. de JOSÉ PÉREZ" → "transf de jose perez".
     *
     * <p>Accents are removed by canonical decomposition rather than by a fixed translation table,
     * which covers every accented character rather than an enumerated list.
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }

        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        String unaccented = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lowered = unaccented.toLowerCase(Locale.ROOT);

        return NON_ALPHANUMERIC.matcher(lowered).replaceAll(" ").trim();
    }

    /**
     * The meaningful words of a name, for overlap comparison: very short fragments and the company
     * form suffixes are dropped, duplicates removed, original order kept.
     */
    public static List<String> tokens(String value) {
        List<String> tokens = new ArrayList<>();

        for (String token : normalize(value).split(" ")) {
            if (token.length() >= MIN_TOKEN_LENGTH && !STOP_WORDS.contains(token) && !tokens.contains(token)) {
                tokens.add(token);
            }
        }

        return tokens;
    }
}
