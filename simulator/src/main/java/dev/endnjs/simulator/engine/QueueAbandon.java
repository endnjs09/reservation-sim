package dev.endnjs.simulator.engine;

import java.util.ArrayDeque;
import java.util.SplittableRandom;

/**
 * 대기 이탈 (docs/DECISION_CLAUDE.md): 대기 중 사용자가 순번 조회 응답을 받을 때마다 판단한다.
 * 확률 = 1 − 0.5^(Δt ÷ 반감기), Δt = 직전 판단(첫 판단은 대기 시작) 이후 시뮬레이션 시간 (11.4 persistent와 같은 형태).
 * 최근 queueStallWindowSec 동안 순번이 queueStallMinProgress 미만으로 줄었으면 그 판단은 반감기 절반. 대기 시작 후 창 길이가 안 됐으면 멈춤 판단 없음.
 * 좌석 상황은 모른다: 근거는 기다린 시간과 자기 순번뿐. hardcore·꺼짐은 떠나지 않고 난수도 쓰지 않는다.
 */
final class QueueAbandon {
    private static final long STREAM=0x51A7_E0A8_D0E5_C0DEL;
    private final ChurnPolicy.Type type;
    private final RunConfig config;
    private final SplittableRandom random;
    private final ArrayDeque<long[]> samples=new ArrayDeque<>(); // {시뮬레이션 nanos, 순번}
    private long origin=Long.MIN_VALUE,startSim=-1,lastSim;
    private boolean stalled;
    QueueAbandon(ChurnPolicy.Type type,RunConfig config,SplittableRandom random) { this.type=type;this.config=config;this.random=random; }
    /** 사용자 본래 난수(profile.random)와 따로: 켜고 꺼도 다른 판단의 난수 순서가 바뀌지 않는다. */
    static SplittableRandom stream(RunConfig config,int index) { return new SplittableRandom(new SplittableRandom(config.seed()^STREAM).nextLong()+index); }
    /** 사용자 index의 대기 이탈 판단기. VirtualUser는 이것만 쓴다. */
    static QueueAbandon forUser(RunConfig config,int index,ChurnPolicy.Type type) { return new QueueAbandon(type,config,stream(config,index)); }
    /** 대기열에 (다시) 들어간 순간. 순번 기록과 Δt 기준을 새로 잡는다. */
    void start(long nowNanos,long position) {
        samples.clear();startSim=sim(nowNanos);lastSim=startSim;stalled=false;samples.add(new long[]{startSim,position});
    }
    boolean leave(long nowNanos,long position) {
        long now=sim(nowNanos);
        if(startSim<0) { start(nowNanos,position);return false; }
        double elapsedSec=Math.max(0,now-lastSim)/1e9;lastSim=now;
        stalled=stalled(now,position);samples.add(new long[]{now,position});prune(now);
        if(!config.queueAbandonEnabled() || type==ChurnPolicy.Type.hardcore) return false;
        double halfLife=config.queueHalfLifeSec().get(type.name())*(stalled ? .5 : 1);
        return random.nextDouble()<1-Math.pow(.5,elapsedSec/halfLife);
    }
    /** 직전 판단이 "줄 멈춤" 상태였는지. */
    boolean stalled() { return stalled; }
    private boolean stalled(long now,long position) {
        long window=Math.round(config.queueStallWindowSec()*1e9);
        if(now-startSim<window) return false;
        long[] base=null;
        for(long[] sample:samples) { if(sample[0]<=now-window) base=sample;else break; }
        if(base==null || base[1]<=0) return false;
        return base[1]-position<config.queueStallMinProgress()*base[1];
    }
    /** 창 시작 시각 이전의 기록은 마지막 하나만 남긴다. */
    private void prune(long now) {
        long window=Math.round(config.queueStallWindowSec()*1e9);
        while(samples.size()>1) {
            var it=samples.iterator();it.next();long[] second=it.next();
            if(second[0]<=now-window) samples.removeFirst();else break;
        }
    }
    /** 첫 대기 시작을 0으로 한 시뮬레이션 시간 (nanoTime 절댓값에 배속을 곱하지 않는다). */
    private long sim(long nanos) { if(origin==Long.MIN_VALUE) origin=nanos;return (nanos-origin)*config.timeScale(); }
}
