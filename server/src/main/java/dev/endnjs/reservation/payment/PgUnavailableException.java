package dev.endnjs.reservation.payment;

public class PgUnavailableException extends RuntimeException {
    public PgUnavailableException(String message) { super(message); }
    public PgUnavailableException(String message, Throwable cause) { super(message, cause); }
}
