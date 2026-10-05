package dev.endnjs.simulator.engine;

import java.util.Map;
import java.util.SplittableRandom;

public enum Persona {
    FAST("fast"), NORMAL("normal"), SLOW("slow");
    private final String key;
    Persona(String key) { this.key = key; }
    public String key() { return key; }
    public record Range(double min, double max) {
        public Range {
            if (!Double.isFinite(min) || !Double.isFinite(max) || min < 0 || max < min || max > Long.MAX_VALUE / 1000.0)
                throw new IllegalArgumentException("Invalid time range");
        }
        public long sampleMillis(SplittableRandom random) { return Math.round((min == max ? min : random.nextDouble(min, max)) * 1000); }
    }
    public record Timing(Range selectSec, Range authSec, double refreshSec) {
        public Timing {
            if (selectSec == null || authSec == null || !Double.isFinite(refreshSec) || refreshSec <= 0 || refreshSec > Long.MAX_VALUE / 1000.0)
                throw new IllegalArgumentException("Invalid persona timing");
        }
        public long refreshMillis() { return Math.max(1, Math.round(refreshSec * 1000)); }
    }
    public static Persona sample(Map<String, Integer> mix, SplittableRandom random) {
        int draw = random.nextInt(100);
        for (Persona persona : values()) {
            draw -= mix.get(persona.key());
            if (draw < 0) return persona;
        }
        throw new IllegalArgumentException("Persona percentages must sum to 100");
    }
}
