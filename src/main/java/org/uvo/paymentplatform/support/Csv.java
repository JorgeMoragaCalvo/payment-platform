package org.uvo.paymentplatform.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 CSV reading, with the two behaviours the statement reader depends on:
 *
 * <ul>
 *   <li><b>Quoting with no escape character.</b> A field may be quoted, a doubled quote inside a
 *       quoted field is one literal quote, and a delimiter or newline inside quotes is data. A
 *       backslash is never special — banks emit plain quoted fields, and a backslash in a
 *       description must stay a backslash.
 *   <li><b>A blank line is an empty row, not a skipped one.</b> The reader scans the first rows
 *       looking for the header, and banks print account details above it with blank spacers in
 *       between; dropping those rows would shift every index and find the header in the wrong place.
 * </ul>
 *
 * <p>Written by hand rather than pulled from a CSV library on purpose: this is the whole of what the
 * importer needs, the format is fully specified, and the fixtures exercise it directly — which is
 * cheaper to keep correct than a dependency whose builder API changes between versions.
 */
public final class Csv {

    private Csv() {
    }

    /** @return one list of cells per line, blank lines included as empty rows. */
    public static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder field = new StringBuilder();

        boolean inQuotes = false;
        boolean rowPending = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote is one literal quote; a single one closes the field.
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (c == '"' && field.isEmpty()) {
                inQuotes = true;
                rowPending = true;
            } else if (c == delimiter) {
                cells.add(field.toString());
                field.setLength(0);
                rowPending = true;
            } else if (c == '\n' || c == '\r') {
                // Accept CRLF, LF and a lone CR.
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(field.toString());
                field.setLength(0);
                rows.add(finish(cells));
                cells = new ArrayList<>();
                rowPending = false;
            } else {
                field.append(c);
                rowPending = true;
            }
        }

        // Anything still buffered is a final line without a trailing newline. A file that does end
        // with a newline must not gain a phantom empty row.
        if (rowPending || !field.isEmpty()) {
            cells.add(field.toString());
            rows.add(finish(cells));
        }

        return rows;
    }

    /** A line holding nothing at all becomes an empty row rather than a row with one empty cell. */
    private static List<String> finish(List<String> cells) {
        if (cells.size() == 1 && cells.get(0).isEmpty()) {
            return List.of();
        }
        return List.copyOf(cells);
    }
}
