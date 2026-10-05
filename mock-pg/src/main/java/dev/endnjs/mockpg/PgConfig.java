package dev.endnjs.mockpg;

public record PgConfig(double authFailureRate, double declineRate, double timeoutRate,
        int confirmMinMs, int confirmMaxMs, Long seed) {
    public PgConfig {
        if (!rate(authFailureRate) || !rate(declineRate) || !rate(timeoutRate)
                || confirmMinMs < 0 || confirmMaxMs < confirmMinMs) {
            throw new PgException(400, "INVALID_REQUEST");
        }
    }
    public static PgConfig defaults() { return new PgConfig(0.03, 0.03, 0, 100, 500, null); }
    private static boolean rate(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
}
