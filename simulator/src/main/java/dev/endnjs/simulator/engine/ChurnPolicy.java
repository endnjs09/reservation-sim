package dev.endnjs.simulator.engine;

import java.util.SplittableRandom;

/** Evaluated only after an admitted user's /seats map contains no AVAILABLE seat. */
final class ChurnPolicy {
    enum Type { casual, persistent, hardcore }
    private final Type type;
    private final RunConfig config;
    private final SplittableRandom random;
    private long emptySince=-1, lastCheck, casualDelay;
    ChurnPolicy(Type type, RunConfig config, SplittableRandom random) {
        this.type=type; this.config=config; this.random=random;
    }
    static Type sample(RunConfig config, SplittableRandom random) {
        int draw=random.nextInt(100);
        for (Type type:Type.values()) { draw-=config.churnMix().get(type.name()); if (draw<0) return type; }
        throw new IllegalStateException("Invalid churn mix");
    }
    Type type() { return type; }
    void reset() { emptySince=-1; }
    boolean leave(long nowNanos) {
        if (emptySince<0) {
            emptySince=nowNanos; lastCheck=nowNanos;
            if (type==Type.casual) casualDelay=config.casualLeaveSec().sampleMillis(random)*1_000_000/config.timeScale();
        }
        double elapsedSeconds=Math.max(0,nowNanos-lastCheck)/1_000_000_000.0*config.timeScale();
        lastCheck=nowNanos;
        return switch(type) {
            case casual -> nowNanos-emptySince>=casualDelay;
            case persistent -> random.nextDouble()<1-Math.pow(.5,elapsedSeconds/config.persistentHalfLifeSec());
            case hardcore -> false;
        };
    }
}
