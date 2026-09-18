package org.uvo.paymentplatform.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.uvo.paymentplatform.cartola.CartolaException;
import org.uvo.paymentplatform.service.BankReconciliationService;
import org.uvo.paymentplatform.service.CustomerAccountService;
import org.uvo.paymentplatform.service.CustomerLookupService;
import org.uvo.paymentplatform.service.WebpayCheckoutService;

import java.util.List;

/**
 * One shape for every error the frontend can act on: a list of {@link ApiError}, each naming the
 * field it belongs to. That is what lets the forms attach a message to the input that caused it,
 * the way the previous forms did.
 *
 * <p>Status codes: 422 for input the user can fix, 409 for an action the current state does not
 * allow, 404 for a thing that is not there. Anything unlisted here is a real error and keeps the
 * framework's 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Bean Validation failures on a request body, one entry per field. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<List<ApiError>> invalidBody(MethodArgumentNotValidException e) {
        List<ApiError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> ApiError.of(error.getField(), messageOf(error)))
                .toList();

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(errors);
    }

    /** A term that is neither a customer id nor a RUT with a valid check digit. */
    @ExceptionHandler(CustomerLookupService.InvalidRutException.class)
    public ResponseEntity<List<ApiError>> invalidRut(CustomerLookupService.InvalidRutException e) {
        return unprocessable("search", e.getMessage());
    }

    /** A statement the importer could not read, or one already imported. Message shown as-is. */
    @ExceptionHandler(CartolaException.class)
    public ResponseEntity<List<ApiError>> cartola(CartolaException e) {
        return unprocessable("cartola", e.getMessage());
    }

    /** The gateway could not be reached. Nothing was charged. */
    @ExceptionHandler(WebpayCheckoutService.CheckoutFailedException.class)
    public ResponseEntity<List<ApiError>> checkoutFailed(WebpayCheckoutService.CheckoutFailedException e) {
        return unprocessable("search", e.getMessage());
    }

    @ExceptionHandler(BankReconciliationService.CustomerNotFoundException.class)
    public ResponseEntity<List<ApiError>> customerNotFound(BankReconciliationService.CustomerNotFoundException e) {
        return unprocessable("assignSearch", e.getMessage());
    }

    /** The action is not allowed from the current state — already resolved, already active, and so on. */
    @ExceptionHandler({
            BankReconciliationService.AlreadyResolvedException.class,
            CustomerAccountService.NotAllowedException.class})
    public ResponseEntity<List<ApiError>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(List.of(ApiError.of(null, e.getMessage())));
    }

    private static ResponseEntity<List<ApiError>> unprocessable(String field, String message) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(List.of(ApiError.of(field, message)));
    }

    private static String messageOf(FieldError error) {
        String message = error.getDefaultMessage();
        return message == null ? "Valor inválido." : message;
    }
}
