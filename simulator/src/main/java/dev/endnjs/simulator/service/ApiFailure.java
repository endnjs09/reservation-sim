package dev.endnjs.simulator.service;

public class ApiFailure extends RuntimeException {
    final int status;
    final String code;
    ApiFailure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
}
