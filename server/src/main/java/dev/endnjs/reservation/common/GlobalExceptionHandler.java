package dev.endnjs.reservation.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException exception) {
        if (exception instanceof dev.endnjs.reservation.hold.SeatUnavailableException unavailable) {
            return ResponseEntity.status(409).body(java.util.Map.of("code", exception.code(),
                    "message", exception.getMessage(), "unavailableSeatIds", unavailable.unavailableSeatIds()));
        }
        return ResponseEntity.status(exception.code().httpStatus())
                .body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> validation(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError(ErrorCode.VALIDATION_FAILED, "Invalid request"));
    }
}
