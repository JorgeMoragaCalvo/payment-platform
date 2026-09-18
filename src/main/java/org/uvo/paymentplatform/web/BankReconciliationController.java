package org.uvo.paymentplatform.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.uvo.paymentplatform.config.BankReconciliationProperties;
import org.uvo.paymentplatform.model.BankMovement;
import org.uvo.paymentplatform.model.Customer;
import org.uvo.paymentplatform.repository.BankMovementRepository;
import org.uvo.paymentplatform.security.AuthenticatedUser;
import org.uvo.paymentplatform.service.BankReconciliationService;
import org.uvo.paymentplatform.service.CustomerLookupService;
import org.uvo.paymentplatform.service.ImportCartolaService;
import org.uvo.paymentplatform.service.SettlementReportService;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The reconciliation screen: import a statement, review the deposits it contains, confirm which
 * customer each one paid for, and compare the card processor's payouts against what was charged.
 *
 * <p>Admin-only, re-checked on every request by the filter chain.
 */
@RestController
@RequestMapping("/api/bank-reconciliation")
@PreAuthorize("hasRole('ADMIN')")
public class BankReconciliationController {

    private static final int PAGE_SIZE = 15;
    private static final int SETTLEMENT_LIMIT = 30;
    private static final long MAX_CARTOLA_BYTES = 5L * 1024 * 1024;

    /** Filter value for the movements the import reconciled itself — not a status. */
    private static final String FILTER_AUTO = "auto";

    private static final List<BankMovement.Status> PENDING =
            List.of(BankMovement.Status.UNMATCHED, BankMovement.Status.SUGGESTED);

    private final ImportCartolaService importService;
    private final BankReconciliationService reconciliationService;
    private final SettlementReportService settlementReportService;
    private final CustomerLookupService lookupService;
    private final BankMovementRepository bankMovementRepository;
    private final BankReconciliationProperties properties;

    public BankReconciliationController(ImportCartolaService importService,
                                        BankReconciliationService reconciliationService,
                                        SettlementReportService settlementReportService,
                                        CustomerLookupService lookupService,
                                        BankMovementRepository bankMovementRepository,
                                        BankReconciliationProperties properties) {
        this.importService = importService;
        this.reconciliationService = reconciliationService;
        this.settlementReportService = settlementReportService;
        this.lookupService = lookupService;
        this.bankMovementRepository = bankMovementRepository;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- configuration for the form

    public record BankOption(String key, String label) {
    }

    public record Config(List<BankOption> banks, boolean autoConfirmEnabled) {
    }

    @GetMapping("/config")
    public Config config() {
        List<BankOption> banks = properties.banks() == null
                ? List.of()
                : properties.banks().entrySet().stream()
                        .map(entry -> new BankOption(entry.getKey(), entry.getValue().label()))
                        .toList();

        boolean autoConfirm = properties.autoConfirm() != null && properties.autoConfirm().enabled();

        return new Config(banks, autoConfirm);
    }

    // ---------------------------------------------------------------------------------- import

    public record ImportResponse(String message, int movementCount, int autoConfirmedCount) {
    }

    /**
     * Everything that can go wrong with a bank export — wrong bank selected, an already imported
     * file, a layout we cannot read — surfaces as a message on the form, never as an error page.
     */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public ResponseEntity<?> importCartola(@RequestParam("cartola") MultipartFile cartola,
                                           @RequestParam("bank") String bank,
                                           @AuthenticationPrincipal AuthenticatedUser staff) throws IOException {
        List<ApiError> errors = validateUpload(cartola, bank);

        if (!errors.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(errors);
        }

        ImportCartolaService.ImportResult result = importService.importCartola(
                cartola.getBytes(), cartola.getOriginalFilename(), bank, staff.getId());

        int imported = result.statement().getMovementCount();
        int auto = result.autoConfirmedCount();

        StringBuilder message = new StringBuilder("Cartola importada: " + imported + " movimientos nuevos.");

        // Those payments are already recorded, so say so on the way in rather than leaving staff to
        // notice rows they never confirmed.
        if (auto > 0) {
            message.append(' ').append(auto)
                    .append(auto == 1 ? " se concilió automáticamente." : " se conciliaron automáticamente.");
        }

        return ResponseEntity.ok(new ImportResponse(message.toString(), imported, auto));
    }

    /** The upload rules the previous form declared: required, CSV or text, at most 5 MB, a known bank. */
    private List<ApiError> validateUpload(MultipartFile cartola, String bank) {
        List<ApiError> errors = new java.util.ArrayList<>();

        if (cartola == null || cartola.isEmpty()) {
            errors.add(ApiError.of("cartola", "Seleccione el archivo de la cartola."));
        } else {
            String name = cartola.getOriginalFilename() == null ? "" : cartola.getOriginalFilename().toLowerCase(Locale.ROOT);

            if (!name.endsWith(".csv") && !name.endsWith(".txt")) {
                errors.add(ApiError.of("cartola", "La cartola debe ser un archivo CSV."));
            }
            if (cartola.getSize() > MAX_CARTOLA_BYTES) {
                errors.add(ApiError.of("cartola", "El archivo supera el tamaño máximo permitido (5 MB)."));
            }
        }

        if (bank == null || properties.banks() == null || !properties.banks().containsKey(bank)) {
            errors.add(ApiError.of("bank", "Seleccione un banco válido."));
        }

        return errors;
    }

    // ------------------------------------------------------------------------------- movements

    public record MovementDto(long id,
                              LocalDate postedAt,
                              String description,
                              String reference,
                              int amountClp,
                              String direction,
                              String status,
                              Long empresaId,
                              String customerName,
                              Integer matchConfidence,
                              String matchReason,
                              boolean autoConfirmed,
                              boolean hasPayment,
                              boolean isResolved) {
    }

    public record Counts(long suggested, long unmatched, long auto, long matched, long ignored, long pending) {
    }

    public record MovementPage(List<MovementDto> movements, int page, int totalPages, long totalCount, Counts counts) {
    }

    /**
     * The queue. With no filter, what the import could not settle plus what it reconciled on its
     * own — a payment made with nobody watching has to be visible where staff already look. The
     * filters are disjoint on purpose: {@code auto} and {@code matched} both describe the same
     * status, so the latter is narrowed to the rows a person resolved.
     */
    @GetMapping("/movements")
    public MovementPage movements(@RequestParam(defaultValue = "") String status,
                                  @RequestParam(defaultValue = "0") int page) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), PAGE_SIZE);

        Page<BankMovement> result = switch (status) {
            case "" -> bankMovementRepository.findQueue(PENDING, pageable);
            case FILTER_AUTO -> bankMovementRepository.findByAutoConfirmedTrueOrderByPostedAtDescIdDesc(pageable);
            case "matched" -> bankMovementRepository.findStaffMatched(BankMovement.Status.MATCHED, pageable);
            default -> bankMovementRepository.findByStatusOrderByPostedAtDescIdDesc(
                    BankMovement.Status.fromValue(status), pageable);
        };

        Counts counts = new Counts(
                bankMovementRepository.countByStatus(BankMovement.Status.SUGGESTED),
                bankMovementRepository.countByStatus(BankMovement.Status.UNMATCHED),
                bankMovementRepository.countByAutoConfirmedTrue(),
                bankMovementRepository.countStaffMatched(BankMovement.Status.MATCHED),
                bankMovementRepository.countByStatus(BankMovement.Status.IGNORED),
                bankMovementRepository.countPending(PENDING));

        return new MovementPage(
                result.map(this::toDto).getContent(),
                result.getNumber(),
                result.getTotalPages(),
                result.getTotalElements(),
                counts);
    }

    /** The to-do count behind the navigation badge: pending only, never the auto-confirmed rows. */
    @GetMapping("/pending-count")
    public Map<String, Long> pendingCount() {
        Map<String, Long> body = new LinkedHashMap<>();
        body.put("pending", bankMovementRepository.countPending(PENDING));
        return body;
    }

    // ---------------------------------------------------------------------------- confirmation

    public record ConfirmationDto(MovementDto movement,
                                  long customerId,
                                  String customerName,
                                  String customerRut,
                                  Integer chargeAmount,
                                  int shortfall) {
    }

    /**
     * What the confirm step shows before money moves: which company, how much arrived, and whether
     * the deposit falls short of the plan charge. A payment always buys a full period, so a partial
     * transfer has to be a visible choice.
     */
    @GetMapping("/movements/{id}/confirmation")
    public ResponseEntity<ConfirmationDto> confirmation(@PathVariable long id, @RequestParam long empresaId) {
        return reconciliationService.confirmation(id, empresaId)
                .map(c -> new ConfirmationDto(
                        toDto(c.movement()),
                        c.customer().getId(),
                        c.customer().getName(),
                        c.customer().getRut(),
                        c.chargeAmount(),
                        c.shortfall()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record ConfirmRequest(@NotNull(message = "Seleccione la empresa a conciliar.") Long empresaId) {
    }

    /**
     * Records the payment — the only action here that moves money. The company id is required with
     * no fallback to the row's suggestion, so a replayed or stale request can only ever record the
     * payment against the company the operator was looking at. 409 when nothing was left to claim.
     */
    @PostMapping("/movements/{id}/confirm")
    public ResponseEntity<MovementDto> confirm(@PathVariable long id,
                                               @Valid @RequestBody ConfirmRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser staff) {
        return reconciliationService.confirm(id, request.empresaId(), staff.getId())
                .map(payment -> bankMovementRepository.findById(id).map(this::toDto).orElseThrow())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @PostMapping("/movements/{id}/ignore")
    public MovementDto ignore(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser staff) {
        return toDto(reconciliationService.ignore(id, staff.getId()));
    }

    @PostMapping("/movements/{id}/return-to-queue")
    public MovementDto returnToQueue(@PathVariable long id) {
        return toDto(reconciliationService.returnToQueue(id));
    }

    public record AssignRequest(@NotBlank(message = "Ingrese un RUT o ID de cliente.") String search) {
    }

    /**
     * Manual assignment for a deposit the engine could not place. Resolves the RUT or id the same
     * way the staff lookup does, and hands back the confirmation preview — it records nothing
     * itself, so a hand-assigned deposit is checked against the plan charge too.
     */
    @PostMapping("/movements/{id}/assign")
    public ResponseEntity<?> assign(@PathVariable long id, @Valid @RequestBody AssignRequest request) {
        Customer customer = lookupService.byRutOrId(request.search()).orElse(null);

        if (customer == null) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .body(List.of(ApiError.of("assignSearch", "No se encontró ninguna empresa con ese RUT o ID.")));
        }

        return confirmation(id, customer.getId());
    }

    // ----------------------------------------------------------------------------- settlements

    public record SettlementRow(MovementDto deposit,
                                LocalDate chargeDate,
                                int charged,
                                int expected,
                                int deposited,
                                int difference,
                                boolean matches) {
    }

    public record Settlements(List<SettlementRow> rows, int deposits, int mismatched, int totalDifference) {
    }

    /** Read-only: those charges are already recorded, so nothing here may write a payment. */
    @GetMapping("/settlements")
    public Settlements settlements() {
        List<SettlementReportService.DepositReconciliation> rows = settlementReportService.forDeposits(SETTLEMENT_LIMIT);
        SettlementReportService.Summary summary = settlementReportService.summary(SETTLEMENT_LIMIT);

        return new Settlements(
                rows.stream().map(r -> new SettlementRow(
                        toDto(r.movement()), r.chargeDate(), r.charged(), r.expected(),
                        r.deposited(), r.difference(), r.matches())).toList(),
                summary.deposits(),
                summary.mismatched(),
                summary.difference());
    }

    // ------------------------------------------------------------------------------------ dto

    private MovementDto toDto(BankMovement m) {
        Customer customer = m.getCustomer();

        return new MovementDto(
                m.getId(),
                m.getPostedAt(),
                m.getDescription(),
                m.getReference(),
                m.getAmountClp(),
                m.getDirection().value(),
                m.getStatus().value(),
                customer == null ? null : customer.getId(),
                customer == null ? null : customer.getName(),
                m.getMatchConfidence(),
                m.getMatchReason(),
                Boolean.TRUE.equals(m.getAutoConfirmed()),
                m.getPayment() != null,
                m.isResolved());
    }
}
