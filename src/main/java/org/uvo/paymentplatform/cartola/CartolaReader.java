package org.uvo.paymentplatform.cartola;

import org.uvo.paymentplatform.config.BankReconciliationProperties;
import org.uvo.paymentplatform.model.BankMovement;
import org.uvo.paymentplatform.support.Csv;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an uploaded statement CSV into {@link Movement} objects.
 *
 * <p>No Chilean bank publishes an account API, so the input is whatever the user downloaded from
 * their bank's portal. Rather than a class per bank, the differences that actually matter — which
 * column is the date, which is the amount — are declared in configuration, so supporting one more
 * bank is a config entry.
 *
 * <p>The reader is tolerant by design: it finds the header row wherever it is (banks print account
 * details above it), accepts {@code ;} or {@code ,} or tab separators, repairs Latin-1 encoded
 * exports, and silently drops lines whose date does not parse — which is how totals and footers get
 * skipped.
 *
 * <p>The per-bank layout is constructor-injected rather than looked up statically, so this stays
 * container-free for tests. It reads bytes rather than a path because the input is an upload.
 */
public class CartolaReader {

    /** How far into the file to look for the header row. */
    private static final int MAX_HEADER_SCAN_ROWS = 25;

    private static final char[] DELIMITER_CANDIDATES = {';', ',', '\t', '|'};
    private static final char DEFAULT_DELIMITER = ';';

    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_REFERENCE_LENGTH = 100;

    /** Bank key → field → candidate labels. */
    private final Map<String, Map<String, List<String>>> banks;

    public CartolaReader(Map<String, Map<String, List<String>>> banks) {
        this.banks = banks;
    }

    /** Builds a reader from the configured bank layouts. */
    public static CartolaReader fromProperties(BankReconciliationProperties properties) {
        Map<String, Map<String, List<String>>> layouts = new LinkedHashMap<>();

        if (properties.banks() != null) {
            properties.banks().forEach((key, bank) -> layouts.put(key, bank.columns()));
        }

        return new CartolaReader(layouts);
    }

    /**
     * Best guess at which configured bank an export came from: the layout that resolves the most
     * columns. Null when none of them can even find a date and a description, which means the file
     * is not a statement we can read.
     */
    public String detect(byte[] contents) {
        List<List<String>> rows = rows(contents);
        String best = null;
        int bestScore = 0;

        for (Map.Entry<String, Map<String, List<String>>> entry : banks.entrySet()) {
            Integer header = findHeader(rows, entry.getValue());

            if (header == null) {
                continue;
            }

            int score = resolvedFieldCount(rows.get(header), entry.getValue());

            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
            }
        }

        return best;
    }

    /** @throws CartolaException when the file has no readable header row. */
    public List<Movement> read(byte[] contents, String bank) {
        Map<String, List<String>> columns = banks.get(bank);

        if (columns == null) {
            throw new CartolaException("El banco seleccionado no está configurado.");
        }

        List<List<String>> rows = rows(contents);
        Integer headerIndex = findHeader(rows, columns);

        if (headerIndex == null) {
            throw new CartolaException(
                    "No se reconocieron las columnas de la cartola. Verifique que el archivo "
                            + "corresponda al banco seleccionado y que conserve su fila de encabezados.");
        }

        ColumnMap map = new ColumnMap(rows.get(headerIndex), columns);
        List<Movement> movements = new ArrayList<>();

        for (List<String> row : rows.subList(headerIndex + 1, rows.size())) {
            Movement movement = toMovement(row, map);

            if (movement != null) {
                movements.add(movement);
            }
        }

        if (movements.isEmpty()) {
            throw new CartolaException("La cartola no contiene movimientos legibles.");
        }

        return movements;
    }

    /**
     * A row becomes a movement only if it has a real date and a non-zero amount. Everything else —
     * blank spacers, subtotals, the "saldo final" footer — fails one of those and is dropped.
     */
    private Movement toMovement(List<String> row, ColumnMap map) {
        java.time.LocalDate postedAt = Dates.parse(map.value(row, ColumnMap.FIELD_DATE));

        if (postedAt == null) {
            return null;
        }

        SignedAmount amount = amountAndDirection(row, map);

        if (amount == null) {
            return null;
        }

        String description = map.value(row, ColumnMap.FIELD_DESCRIPTION);
        description = description == null ? "" : description;
        String reference = map.value(row, ColumnMap.FIELD_REFERENCE);

        return new Movement(
                postedAt,
                truncate(description, MAX_DESCRIPTION_LENGTH),
                reference == null || reference.isEmpty() ? null : truncate(reference, MAX_REFERENCE_LENGTH),
                amount.amount(),
                amount.direction(),
                Glosa.extractRut(description));
    }

    private record SignedAmount(int amount, BankMovement.Direction direction) {
    }

    /**
     * Two shapes are in the wild: separate debit/credit columns, or one signed column. Both
     * collapse to a positive amount plus a direction.
     */
    private SignedAmount amountAndDirection(List<String> row, ColumnMap map) {
        Integer credit = Money.parseClp(map.value(row, ColumnMap.FIELD_CREDIT));
        Integer debit = Money.parseClp(map.value(row, ColumnMap.FIELD_DEBIT));

        if (credit != null && credit != 0) {
            return new SignedAmount(Math.abs(credit), BankMovement.Direction.CREDIT);
        }

        if (debit != null && debit != 0) {
            return new SignedAmount(Math.abs(debit), BankMovement.Direction.DEBIT);
        }

        Integer amount = Money.parseClp(map.value(row, ColumnMap.FIELD_AMOUNT));

        if (amount == null || amount == 0) {
            return null;
        }

        return new SignedAmount(Math.abs(amount),
                amount > 0 ? BankMovement.Direction.CREDIT : BankMovement.Direction.DEBIT);
    }

    /** The first row within the scan window that resolves enough columns to describe a movement. */
    private Integer findHeader(List<List<String>> rows, Map<String, List<String>> columns) {
        int limit = Math.min(rows.size(), MAX_HEADER_SCAN_ROWS);

        for (int i = 0; i < limit; i++) {
            if (new ColumnMap(rows.get(i), columns).isUsable()) {
                return i;
            }
        }

        return null;
    }

    private int resolvedFieldCount(List<String> header, Map<String, List<String>> columns) {
        ColumnMap map = new ColumnMap(header, columns);

        return (int) columns.keySet().stream().filter(map::has).count();
    }

    /** The whole file as rows of UTF-8 strings. */
    private List<List<String>> rows(byte[] contents) {
        char delimiter = detectDelimiter(contents);
        List<List<String>> rows = Csv.parse(decode(contents), delimiter);

        if (!rows.isEmpty()) {
            rows.set(0, stripBom(rows.get(0)));
        }

        return rows;
    }

    /**
     * Chilean exports usually use {@code ;}, because the comma is the decimal mark. Guessing from
     * the most-repeated candidate over the first lines is more reliable than assuming either.
     */
    private char detectDelimiter(byte[] contents) {
        Map<Character, Integer> counts = new LinkedHashMap<>();
        for (char candidate : DELIMITER_CANDIDATES) {
            counts.put(candidate, 0);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.ISO_8859_1))) {

            String line;
            int scanned = 0;

            while (scanned++ < MAX_HEADER_SCAN_ROWS && (line = reader.readLine()) != null) {
                for (char candidate : DELIMITER_CANDIDATES) {
                    int occurrences = 0;
                    for (int i = 0; i < line.length(); i++) {
                        if (line.charAt(i) == candidate) {
                            occurrences++;
                        }
                    }
                    counts.merge(candidate, occurrences, Integer::sum);
                }
            }
        } catch (IOException ignored) {
            // Reading from a byte array cannot fail on I/O; fall through to the default.
        }

        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .orElse(DEFAULT_DELIMITER);
    }

    /**
     * UTF-8 when the bytes are valid UTF-8, Latin-1 otherwise. Banks export both, and a mis-decoded
     * export corrupts exactly the accented company names the match engine needs.
     *
     * <p>Decoded strictly and per file: a REPORT decoder is what distinguishes "this is Latin-1"
     * from "this is UTF-8 with a replacement character in it".
     */
    private String decode(byte[] contents) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        try {
            return decoder.decode(ByteBuffer.wrap(contents)).toString();
        } catch (CharacterCodingException e) {
            return new String(contents, StandardCharsets.ISO_8859_1);
        }
    }

    private List<String> stripBom(List<String> row) {
        if (row.isEmpty() || row.get(0) == null) {
            return row;
        }

        List<String> stripped = new ArrayList<>(row);
        stripped.set(0, stripped.get(0).replaceFirst("^﻿", ""));

        return stripped;
    }

    private String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
