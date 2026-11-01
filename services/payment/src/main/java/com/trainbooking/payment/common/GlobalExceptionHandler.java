package com.trainbooking.payment.common;

import com.trainbooking.payment.payment.model.PaymentConflictException;
import com.trainbooking.payment.refund.model.NoPaymentToRefundException;
import com.trainbooking.payment.stripe.StripeCallException;
import com.trainbooking.payment.stripe.StripeNotConfiguredException;
import com.trainbooking.payment.stripe.StripeRejectedException;
import com.trainbooking.payment.webhook.service.WebhookService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure inside a controller into the shared JSON error shape. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        Map<String, String> byField = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            byField.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        List<ApiError.Detail> details = byField.entrySet().stream().map(entry -> new ApiError.Detail(entry.getKey(), entry.getValue())).toList();
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("validation_failed", "The request body is not valid.", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody() {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("malformed_request", "The request body is missing or is not valid JSON."));
    }

    @ExceptionHandler(WebhookService.InvalidSignatureException.class)
    ResponseEntity<ApiError> badSignature(WebhookService.InvalidSignatureException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of("invalid_signature", e.getMessage()));
    }

    @ExceptionHandler(NoPaymentToRefundException.class)
    ResponseEntity<ApiError> noPayment(NoPaymentToRefundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("no_payment_to_refund", e.getMessage()));
    }

    @ExceptionHandler(PaymentConflictException.class)
    ResponseEntity<ApiError> conflict(PaymentConflictException e) {
        return respond(HttpStatus.CONFLICT, ApiError.of(e.code(), e.getMessage()));
    }

    /** No answer from Stripe: nothing was changed that cannot be repeated, the caller tries again (it keeps the seats held meanwhile). */
    @ExceptionHandler(StripeCallException.class)
    ResponseEntity<ApiError> stripeDown(StripeCallException e) {
        log.warn("Stripe call failed: {}", e.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ApiError.of("stripe_unavailable", "Stripe is not available right now. Try again in a moment."));
    }

    /** Stripe refused the request for good (not a missing answer): repeating it cannot help. */
    @ExceptionHandler(StripeRejectedException.class)
    ResponseEntity<ApiError> stripeRejected(StripeRejectedException e) {
        log.warn("Stripe rejected a request: {}", e.getMessage());
        return respond(HttpStatus.BAD_GATEWAY, ApiError.of("stripe_rejected", "Stripe refused the request."));
    }

    @ExceptionHandler(StripeNotConfiguredException.class)
    ResponseEntity<ApiError> notConfigured(StripeNotConfiguredException e) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ApiError.of("stripe_not_configured", e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute() {
        return respond(HttpStatus.NOT_FOUND, ApiError.of("not_found", "No such route."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> wrongMethod() {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of("method_not_allowed", "This method is not supported for this route."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of("internal_error", "Something went wrong."));
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
