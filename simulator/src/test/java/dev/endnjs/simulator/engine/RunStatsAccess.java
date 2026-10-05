package dev.endnjs.simulator.engine;

/** 다른 패키지 테스트에서 RunStats의 timeout 분류(8.3)를 확인하기 위한 통로. */
public final class RunStatsAccess {
    private RunStatsAccess() {}
    public static boolean timeout(Throwable failure) { return RunStats.timeout(failure); }
}
