package org.uvo.paymentplatform.web;

/**
 * A single field-level error, shaped the way the previous forms reported one so the frontend can
 * attach the message to the input that caused it.
 *
 * @param field   the request field the message belongs to, or null when it applies to the whole
 *                request
 * @param message Spanish, shown to the user as-is
 */
public record ApiError(String field, String message) {

    public static ApiError of(String field, String message) {
        return new ApiError(field, message);
    }
}
