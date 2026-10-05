package dev.endnjs.mockpg;

public class PgException extends RuntimeException {
    private final int status;
    private final String code;
    public PgException(int status, String code) { super(code); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
}
