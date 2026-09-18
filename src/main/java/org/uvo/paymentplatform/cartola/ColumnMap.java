package org.uvo.paymentplatform.cartola;

import org.uvo.paymentplatform.support.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a bank's configured column labels against the header row of an actual export, so the
 * rest of the importer can ask for "date" without knowing whether this bank called it "Fecha" or
 * "Fecha Transacción".
 *
 * <p>Labels match accent- and case-insensitively, and on a prefix, because banks append things: the
 * configured "fecha" matches "Fecha Movimiento".
 */
public class ColumnMap {

    public static final String FIELD_DATE = "date";
    public static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_REFERENCE = "reference";
    public static final String FIELD_CREDIT = "credit";
    public static final String FIELD_DEBIT = "debit";
    public static final String FIELD_AMOUNT = "amount";

    /** Field name → column index. */
    private final Map<String, Integer> indexes = new HashMap<>();

    /**
     * @param header  raw header cells
     * @param columns field name → candidate labels
     */
    public ColumnMap(List<String> header, Map<String, List<String>> columns) {
        List<String> normalized = header.stream().map(Text::normalize).toList();

        columns.forEach((field, candidates) -> {
            for (String candidate : candidates) {
                Integer index = findColumn(normalized, Text.normalize(candidate));

                if (index != null) {
                    indexes.put(field, index);
                    break;
                }
            }
        });
    }

    public boolean has(String field) {
        return indexes.containsKey(field);
    }

    /**
     * Every field the layout needs to describe a movement at all: a date, something to show the
     * staff member, and at least one amount column.
     */
    public boolean isUsable() {
        return has(FIELD_DATE)
                && has(FIELD_DESCRIPTION)
                && (has(FIELD_AMOUNT) || has(FIELD_CREDIT) || has(FIELD_DEBIT));
    }

    public String value(List<String> row, String field) {
        Integer index = indexes.get(field);

        if (index == null || index >= row.size()) {
            return null;
        }

        String cell = row.get(index);

        return cell == null ? null : cell.trim();
    }

    private Integer findColumn(List<String> normalizedHeader, String label) {
        if (label.isEmpty()) {
            return null;
        }

        for (int i = 0; i < normalizedHeader.size(); i++) {
            if (normalizedHeader.get(i).equals(label)) {
                return i;
            }
        }

        // Fall back to a prefix match ("fecha" finding "fecha movimiento"), which is how banks
        // usually differ from the configured label.
        for (int i = 0; i < normalizedHeader.size(); i++) {
            String cell = normalizedHeader.get(i);
            if (!cell.isEmpty() && cell.startsWith(label)) {
                return i;
            }
        }

        return null;
    }
}
