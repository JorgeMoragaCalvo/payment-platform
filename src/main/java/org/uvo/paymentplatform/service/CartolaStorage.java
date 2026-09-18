package org.uvo.paymentplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.uvo.paymentplatform.cartola.CartolaException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Keeps the uploaded statement file for audit: whoever questions a reconciled payment months later
 * needs the export it came from.
 *
 * <p><b>This store must stay private.</b> A bank statement lists every movement of the company's
 * account, so the directory belongs outside any web root, outside anything served statically, and
 * outside whatever sync or backup lands in one.
 */
@Component
public class CartolaStorage {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");

    private final Path root;
    private final Clock clock;

    public CartolaStorage(@Value("${cartolas.root}") String root, Clock clock) {
        this.root = Paths.get(root);
        this.clock = clock;
    }

    /**
     * Stores the upload under {@code YYYY-MM/<hash>.csv} and returns the relative path recorded on
     * the statement row. The hash is the file name, so re-storing the same bytes is idempotent.
     */
    public String put(String hash, byte[] contents) {
        String relative = LocalDate.now(clock).format(MONTH) + "/" + hash + ".csv";
        Path target = root.resolve(relative);

        try {
            Files.createDirectories(target.getParent());
            Files.write(target, contents);
        } catch (IOException e) {
            throw new CartolaException("No se pudo guardar el archivo de la cartola.");
        }

        return relative;
    }

    /**
     * Removes a stored upload. Used when the import failed: the statement row was rolled back, so
     * the file is an orphan that the file-hash guard would never point at again.
     */
    public void delete(String relative) {
        try {
            Files.deleteIfExists(root.resolve(relative));
        } catch (IOException ignored) {
            // Best effort: an orphaned file is untidy, not harmful, and the import already failed.
        }
    }
}
