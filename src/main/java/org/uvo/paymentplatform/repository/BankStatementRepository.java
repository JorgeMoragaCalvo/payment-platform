package org.uvo.paymentplatform.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uvo.paymentplatform.model.BankStatement;

public interface BankStatementRepository extends JpaRepository<BankStatement, Long> {

    /**
     * Blocks re-uploading the same export. This is a read, so it cannot stop two simultaneous
     * uploads of the same file — the unique index on {@code file_hash} is what actually does.
     */
    boolean existsByFileHash(String fileHash);
}
