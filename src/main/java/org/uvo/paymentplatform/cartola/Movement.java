package org.uvo.paymentplatform.cartola;

import org.uvo.paymentplatform.model.BankMovement;
import org.uvo.paymentplatform.support.Text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;

/**
 * One parsed statement line, normalized away from whatever the bank's export looked like.
 * Immutable — the importer turns these into persisted movements.
 *
 * @param amount always positive; the sign lives in {@code direction}
 * @param counterpartyRut normalized RUT found in the description, when one validated
 */
public record Movement(LocalDate postedAt,
                       String description,
                       String reference,
                       int amount,
                       BankMovement.Direction direction,
                       String counterpartyRut) {

    /**
     * Fingerprint used to recognise the same movement arriving in a second statement. Exports are
     * requested by date range and those ranges overlap constantly, so without this the same deposit
     * would be offered for reconciliation twice.
     *
     * <p>The description is normalized first: the same line re-exported can differ in spacing,
     * casing or accents. The direction contributes its stored lowercase value, and a null reference
     * contributes an empty string, so the hash matches the source project's byte for byte.
     */
    public String rowHash() {
        String basis = String.join("|",
                postedAt.toString(),
                direction.value(),
                String.valueOf(amount),
                reference == null ? "" : reference,
                Text.normalize(description));

        return sha256Hex(basis);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
