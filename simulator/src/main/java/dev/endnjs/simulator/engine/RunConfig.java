package dev.endnjs.simulator.engine;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

public record RunConfig(int rows, int cols, List<Grade> grades, int users, List<Arrival> arrival,
        Map<String, Integer> personaMix, Map<String, Persona.Timing> personas,
        double abandonRate, long seed, int maxActive, int admitPerSec, int admissionTtlSec, boolean closeQueueOnSoldOut,
        int holdTtlSec, int confirmDeadlineSec, double authFailureRate, double declineRate, int confirmMinMs, int confirmMaxMs,
        double timeoutRate, String strategy, boolean dbBackstop, int timeLimitSec, Targets targets,
        Integer timeScale, Integer saleDurationSec, Map<String,Integer> churnMix, Persona.Range casualLeaveSec,
        Double persistentHalfLifeSec, Map<String,Double> revisitProb, Integer maxSeatsPerUser,
        Map<String,Integer> ticketCountMix, Double adjacentRequiredRate, Map<String,Integer> paymentMix,
        Integer depositDeadlineSec, Double depositNoPayRate, Integer returnDelaySec, Integer reopenWindowSec,
        Double cancelAfterPurchaseRate, Map<String,Persona.Range> priceStepSec, String queueMode, Integer busyMaxExtraSec, Integer requestTimeoutMs, String label, String notes, Thresholds thresholds,
        Boolean queueAbandonEnabled, Map<String,Double> queueHalfLifeSec, Double queueStallWindowSec, Double queueStallMinProgress,
        Boolean seatsRateLimitEnabled, Double seatsMinIntervalSec, Integer seatsCacheSec,
        Boolean revisitRetryEnabled, Double revisitRetryHardcoreMultiplier, Persona.Range revisitRetryPersistentSec) {
    public record Grade(String name, int rows, int price) {
        public Grade {
            require(name != null && Set.of("VIP", "S", "A", "B").contains(name) && rows >= 0 && price >= 0, "Invalid grade");
        }
    }
    public record Arrival(int percent, double fromSec, double toSec) {
        public Arrival {
            require(percent >= 0 && percent <= 100 && Double.isFinite(fromSec) && Double.isFinite(toSec)
                    && fromSec >= 0 && toSec >= fromSec && toSec <= Long.MAX_VALUE / 1000.0, "Invalid arrival band");
        }
    }
    public record Targets(String server, String pg,String queue) {
        public Targets(String server,String pg) { this(server,pg,"http://localhost:8082"); }
        public Targets { queue=queue==null ? "http://localhost:8082" : queue;validateUrl(server); validateUrl(pg);validateUrl(queue); }
        private static void validateUrl(String value) {
            require(value != null, "Missing target URL");
            URI uri = URI.create(value);
            require(uri.isAbsolute() && Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                    && uri.getQuery() == null && uri.getFragment() == null, "Invalid target URL");
        }
    }
    /** minSamplesPerWindow: 1초 창의 예약 서버 요청이 이보다 적으면 p95·에러율 판정에서 뺀다. 값 없는 예전 기록은 0(빼지 않음), 새 실행 기본 20. */
    public record Thresholds(double p95WarnMs,double p95SloMs,double errWarnPct,double errBadPct,double poolWarnPct,double poolBadPct,Integer minSamplesPerWindow) {
        public Thresholds {
            require(Double.isFinite(p95WarnMs) && Double.isFinite(p95SloMs) && p95WarnMs>=0 && p95SloMs>p95WarnMs,"Invalid latency thresholds");
            require(Double.isFinite(errWarnPct) && Double.isFinite(errBadPct) && errWarnPct>=0 && errBadPct>errWarnPct && errBadPct<=100,"Invalid error thresholds");
            require(Double.isFinite(poolWarnPct) && Double.isFinite(poolBadPct) && poolWarnPct>=0 && poolBadPct>poolWarnPct && poolBadPct<=100,"Invalid pool thresholds");
            minSamplesPerWindow=minSamplesPerWindow==null ? 0 : minSamplesPerWindow;require(minSamplesPerWindow>=0,"Invalid minimum samples");
        }
        public static Thresholds defaults() { return new Thresholds(100,300,8,25,70,90,20); }
    }
    public RunConfig {
        queueMode=queueMode==null ? "EXTERNAL" : queueMode;require(Set.of("EMBEDDED","EXTERNAL").contains(queueMode),"Invalid queue mode");
        busyMaxExtraSec=busyMaxExtraSec==null ? 510 : busyMaxExtraSec;requestTimeoutMs=requestTimeoutMs==null ? 10000 : requestTimeoutMs;
        require(busyMaxExtraSec>0 && requestTimeoutMs>0,"Invalid busy limit/request timeout");
        label=label==null ? "" : label;notes=notes==null ? "" : notes;thresholds=thresholds==null ? Thresholds.defaults() : thresholds;
        require(label.length()<=200 && notes.length()<=4000,"Label/notes too long");
        // Legacy saved summaries contain neither field; their user timing was real time (1x).
        churnMix = churnMix == null ? Map.of("casual",70,"persistent",20,"hardcore",10) : Map.copyOf(churnMix);
        casualLeaveSec = casualLeaveSec == null ? new Persona.Range(0,60) : casualLeaveSec;
        persistentHalfLifeSec = persistentHalfLifeSec == null ? 180.0 : persistentHalfLifeSec;
        revisitProb = revisitProb == null ? Map.of("casual",.1,"persistent",.5,"hardcore",.9) : Map.copyOf(revisitProb);
        maxSeatsPerUser = maxSeatsPerUser == null ? 4 : maxSeatsPerUser;
        ticketCountMix = ticketCountMix == null ? Map.of("1",30,"2",55,"3",10,"4",5) : Map.copyOf(ticketCountMix);
        adjacentRequiredRate = adjacentRequiredRate == null ? .9 : adjacentRequiredRate;
        paymentMix = paymentMix == null ? Map.of("card",85,"deposit",15) : Map.copyOf(paymentMix);
        depositDeadlineSec = depositDeadlineSec == null ? 60 : depositDeadlineSec;
        depositNoPayRate = depositNoPayRate == null ? .4 : depositNoPayRate;
        returnDelaySec = returnDelaySec == null ? 30 : returnDelaySec;
        reopenWindowSec = reopenWindowSec == null ? 50 : reopenWindowSec;
        cancelAfterPurchaseRate = cancelAfterPurchaseRate == null ? .02 : cancelAfterPurchaseRate;
        priceStepSec = priceStepSec == null ? Map.of("fast",new Persona.Range(2,5),"normal",new Persona.Range(5,15),
                "slow",new Persona.Range(15,40)) : Map.copyOf(priceStepSec);
        validateMix(ticketCountMix,Set.of("1","2","3","4"),"ticket count");
        validateMix(paymentMix,Set.of("card","deposit"),"payment");
        require(maxSeatsPerUser>0 && depositDeadlineSec>0 && returnDelaySec>0 && reopenWindowSec>0,"Invalid reservation timing/limits");
        for(var entry:ticketCountMix.entrySet()) require(entry.getValue()==0 || Integer.parseInt(entry.getKey())<=maxSeatsPerUser,"Ticket count exceeds maxSeatsPerUser");
        require(rate(adjacentRequiredRate) && rate(depositNoPayRate) && rate(cancelAfterPurchaseRate),"Invalid purchase probability");
        require(priceStepSec.keySet().equals(Set.of("fast","normal","slow")) && priceStepSec.values().stream().allMatch(java.util.Objects::nonNull),"Missing price step timing");
        var churnKeys = Set.of("casual","persistent","hardcore");
        require(churnMix.keySet().equals(churnKeys) && churnMix.values().stream().allMatch(v -> v != null && v >= 0 && v <= 100)
                && churnMix.values().stream().mapToInt(Integer::intValue).sum()==100,"Invalid churn mix");
        require(Double.isFinite(persistentHalfLifeSec) && persistentHalfLifeSec > 0,"Invalid persistent half life");
        require(revisitProb.keySet().equals(churnKeys) && revisitProb.values().stream().allMatch(v -> v != null && rate(v)),"Invalid revisit probability");
        // 대기 이탈 (docs/DECISION_CLAUDE.md). 값이 없는 저장 기록은 이 규칙 전의 실행이라 꺼짐. 새 실행 기본값(defaults)은 켬.
        queueAbandonEnabled = queueAbandonEnabled != null && queueAbandonEnabled;
        queueHalfLifeSec = queueHalfLifeSec == null ? Map.of("casual",180.0,"persistent",600.0) : Map.copyOf(queueHalfLifeSec);
        queueStallWindowSec = queueStallWindowSec == null ? 60.0 : queueStallWindowSec;
        queueStallMinProgress = queueStallMinProgress == null ? .05 : queueStallMinProgress;
        require(queueHalfLifeSec.keySet().equals(Set.of("casual","persistent")) && queueHalfLifeSec.values().stream().allMatch(v -> v != null && Double.isFinite(v) && v > 0),"Invalid queue half life");
        require(Double.isFinite(queueStallWindowSec) && queueStallWindowSec > 0 && rate(queueStallMinProgress),"Invalid queue stall rule");
        // 예약 서버 GET /seats 새로고침 제한·캐시 (docs/DECISION_CLAUDE.md). 값이 없는 저장 기록은 둘 다 없던 실행. 새 실행 기본값: 제한 켬 1초, 캐시 끔.
        seatsRateLimitEnabled = seatsRateLimitEnabled != null && seatsRateLimitEnabled;
        seatsMinIntervalSec = seatsMinIntervalSec == null ? 1.0 : seatsMinIntervalSec;
        seatsCacheSec = seatsCacheSec == null ? 0 : seatsCacheSec;
        require(Double.isFinite(seatsMinIntervalSec) && seatsMinIntervalSec > 0 && seatsCacheSec >= 0 && seatsCacheSec <= 60,"Invalid seats refresh/cache");
        // 재방문 대기 중 재시도 (docs/DECISION_CLAUDE.md). 값이 없는 저장 기록은 이 규칙 전 실행이라 꺼짐. 새 실행 기본: 켬, hardcore 새로고침×2, persistent 10~20초
        revisitRetryEnabled = revisitRetryEnabled != null && revisitRetryEnabled;
        revisitRetryHardcoreMultiplier = revisitRetryHardcoreMultiplier == null ? 2.0 : revisitRetryHardcoreMultiplier;
        revisitRetryPersistentSec = revisitRetryPersistentSec == null ? new Persona.Range(10,20) : revisitRetryPersistentSec;
        require(Double.isFinite(revisitRetryHardcoreMultiplier) && revisitRetryHardcoreMultiplier > 0 && revisitRetryPersistentSec.min() > 0,"Invalid revisit retry");
        timeScale = timeScale == null ? 1 : timeScale;
        saleDurationSec = saleDurationSec == null ? 1200 : saleDurationSec;
        require(rows >= 1 && rows <= 26 && cols > 0 && (long) rows * cols <= Integer.MAX_VALUE, "Invalid seats");
        require(grades != null && !grades.isEmpty(), "Missing grades");
        var names = new HashSet<String>();
        long gradeRows = 0;
        for (Grade grade : grades) { require(grade != null && names.add(grade.name()), "Duplicate/null grade"); gradeRows += grade.rows(); }
        require(gradeRows == rows, "Grade row sum must equal rows");
        require(users > 0, "users must be positive");
        require(arrival != null && !arrival.isEmpty() && arrival.stream().allMatch(java.util.Objects::nonNull)
                && arrival.stream().mapToLong(Arrival::percent).sum() == 100, "Arrival percentages must sum to 100");
        var keys = Set.of("fast", "normal", "slow");
        require(personaMix != null && personaMix.keySet().equals(keys) && personaMix.values().stream()
                .allMatch(value -> value != null && value >= 0 && value <= 100)
                && personaMix.values().stream().mapToInt(Integer::intValue).sum() == 100, "Invalid persona mix");
        require(personas != null && personas.keySet().equals(keys) && personas.values().stream().allMatch(java.util.Objects::nonNull), "Missing persona timing");
        require(rate(abandonRate) && rate(authFailureRate) && rate(declineRate) && rate(timeoutRate), "Invalid probability");
        require(maxActive > 0 && admitPerSec > 0 && admissionTtlSec > 0 && holdTtlSec > 0 && confirmDeadlineSec > 0, "Invalid queue/hold limits");
        require(confirmMinMs >= 0 && confirmMaxMs >= confirmMinMs, "Invalid approval delay");
        require(strategy != null && Set.of("conditional", "pessimistic", "optimistic", "naive").contains(strategy), "Invalid strategy");
        require(timeLimitSec > 0 && targets != null, "Invalid run limit/targets");
        require(Set.of(1, 2, 4).contains(timeScale) && saleDurationSec > 0, "Invalid sale timing");
        grades = List.copyOf(grades); arrival = List.copyOf(arrival);
        personaMix = Map.copyOf(personaMix); personas = Map.copyOf(personas);
    }
    public long sampleArrivalMillis(SplittableRandom random) {
        int draw = random.nextInt(100);
        for (Arrival band : arrival) {
            draw -= band.percent();
            if (draw < 0) return Math.round((band.fromSec() == band.toSec() ? band.fromSec()
                    : random.nextDouble(band.fromSec(), band.toSec())) * 1000);
        }
        throw new IllegalStateException("Invalid arrival distribution");
    }
    public static RunConfig defaults() {
        return new RunConfig(10, 10, List.of(new Grade("VIP", 1, 150000), new Grade("S", 2, 120000),
                new Grade("A", 3, 90000), new Grade("B", 4, 60000)), 2000,
                List.of(new Arrival(60, 0, 5), new Arrival(30, 5, 60), new Arrival(10, 60, 300)),
                Map.of("fast", 20, "normal", 70, "slow", 10),
                Map.of("fast", timing(.2, .8, 5, 15, 1), "normal", timing(1, 3, 10, 40, 2), "slow", timing(3, 8, 60, 240, 3)),
                .04, 42, 200, 20, 420, true, 420, 30, .03, .03, 100, 500, 0,
                "conditional", true, 1800, new Targets("http://localhost:8080", "http://localhost:8081"), 4, 1200, Map.of("casual",70,"persistent",20,"hardcore",10), new Persona.Range(0,60),
                180.0, Map.of("casual",.1,"persistent",.5,"hardcore",.9),4,Map.of("1",30,"2",55,"3",10,"4",5),
                .9,Map.of("card",85,"deposit",15),60,.4,30,50,.02,
                Map.of("fast",new Persona.Range(2,5),"normal",new Persona.Range(5,15),"slow",new Persona.Range(15,40)),"EXTERNAL",510,10000,"","",Thresholds.defaults(),
                true,Map.of("casual",180.0,"persistent",600.0),60.0,.05,
                true,1.0,0,
                true,2.0,new Persona.Range(10,20));
    }
    public java.time.Duration realDuration(int simulationSeconds) {
        return java.time.Duration.ofSeconds(simulationSeconds).dividedBy(timeScale);
    }
    public long realMillis(long simulationMillis) { return simulationMillis/timeScale; }
    private static void validateMix(Map<String,Integer> mix,Set<String> keys,String name) {
        require(mix.keySet().equals(keys) && mix.values().stream().allMatch(v -> v!=null && v>=0 && v<=100)
                && mix.values().stream().mapToInt(Integer::intValue).sum()==100,"Invalid "+name+" mix");
    }
    private static Persona.Timing timing(double selectMin, double selectMax, double authMin, double authMax, double refresh) {
        return new Persona.Timing(new Persona.Range(selectMin, selectMax), new Persona.Range(authMin, authMax), refresh);
    }
    private static boolean rate(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
