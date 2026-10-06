package dev.endnjs.simulator.engine;

import java.util.SplittableRandom;

/**
 * 재방문 대기 중 재시도 (docs/DECISION_CLAUDE.md): 취소표 오픈과 별개로 대기열 진입을 주기적으로 다시 시도한다.
 * hardcore: 간격 = 페르소나 새로고침 × revisitRetryHardcoreMultiplier, ±20%, 판매 종료까지.
 * persistent: 간격 U(revisitRetryPersistentSec), 시도마다 1 − 0.5^(Δt ÷ persistentHalfLifeSec)로 재시도를 그만둠 (그 뒤는 취소표 오픈 때만).
 * casual·꺼짐: 재시도 없음, 난수도 쓰지 않음. 판단용 난수는 사용자 본래 난수와 별도 스트림.
 */
final class RevisitRetry {
    private static final long STREAM=0x7E71_5170_EE7A_1A57L;
    private final ChurnPolicy.Type type;
    private final RunConfig config;
    private final Persona persona;
    private final SplittableRandom random;
    private boolean quit;
    RevisitRetry(ChurnPolicy.Type type,Persona persona,RunConfig config,SplittableRandom random) { this.type=type;this.persona=persona;this.config=config;this.random=random; }
    static SplittableRandom stream(RunConfig config,int index) { return new SplittableRandom(new SplittableRandom(config.seed()^STREAM).nextLong()+index); }
    /** 사용자 index의 재시도 판단기. VirtualUser는 이것만 쓴다. */
    static RevisitRetry forUser(RunConfig config,int index,ChurnPolicy.Type type,Persona persona) { return new RevisitRetry(type,persona,config,stream(config,index)); }
    /** 아직 재시도를 하는 사용자인지. */
    boolean active() {
        return config.revisitRetryEnabled() && !quit && (type==ChurnPolicy.Type.hardcore || type==ChurnPolicy.Type.persistent);
    }
    /** 다음 시도까지 시뮬레이션 ms. */
    long nextIntervalSimMillis() {
        if(type==ChurnPolicy.Type.hardcore) {
            double base=config.personas().get(persona.key()).refreshSec()*config.revisitRetryHardcoreMultiplier()*1000;
            return Math.max(1,Math.round(base*random.nextDouble(.8,1.2)));
        }
        return Math.max(1,config.revisitRetryPersistentSec().sampleMillis(random));
    }
    /** 시도 직전 판단: persistent는 직전 시도(첫 판단은 재방문 대기 시작) 뒤 흐른 시뮬레이션 초로 그만둘지 정한다. 그만두면 true. */
    boolean quitsBeforeAttempt(double elapsedSimSec) {
        if(type!=ChurnPolicy.Type.persistent) return false;
        if(random.nextDouble()<1-Math.pow(.5,Math.max(0,elapsedSimSec)/config.persistentHalfLifeSec())) quit=true;
        return quit;
    }
}
