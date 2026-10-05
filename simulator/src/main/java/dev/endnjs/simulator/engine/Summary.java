package dev.endnjs.simulator.engine;

import java.time.Instant;
import java.util.Map;

public record Summary(Instant startedAt,long durationMs,RunConfig config,Requests requests,
        Map<String,Long> responses,Map<String,Double> avgLatencyMs,Map<String,Long> outcomes,
        Map<String,Map<String,Long>> outcomesByPersona,Map<String,Long> events,
        Map<String,Map<String,Long>> outcomesByChurn,Long seatsSold,Map<String,Long> seatsByGrade,Map<String,Object> signals,Map<String,Object> latencyMs,Map<String,Object> clientLatencyMs,Map<String,Long> errorClasses) {
    public Summary(Instant startedAt,long durationMs,RunConfig config,Requests requests,Map<String,Long> responses,
            Map<String,Double> avgLatencyMs,Map<String,Long> outcomes,Map<String,Map<String,Long>> outcomesByPersona,
            Map<String,Long> events,Map<String,Map<String,Long>> outcomesByChurn,Long seatsSold,Map<String,Long> seatsByGrade) {
        this(startedAt,durationMs,config,requests,responses,avgLatencyMs,outcomes,outcomesByPersona,events,outcomesByChurn,seatsSold,seatsByGrade,Map.of(),Map.of(),Map.of(),Map.of());
    }
    public Summary withMetrics(Map<String,Object> signals,Map<String,Object> latency,Map<String,Object> client,Map<String,Long> classes) {
        return new Summary(startedAt,durationMs,config,requests,responses,avgLatencyMs,outcomes,outcomesByPersona,events,outcomesByChurn,seatsSold,seatsByGrade,signals,latency,client,classes);
    }
    public Summary {
        outcomesByChurn=outcomesByChurn==null ? Map.of() : Map.copyOf(outcomesByChurn);
        seatsByGrade=seatsByGrade==null ? Map.of() : Map.copyOf(seatsByGrade);
        signals=signals==null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(signals));
        latencyMs=latencyMs==null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(latencyMs));
        clientLatencyMs=clientLatencyMs==null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(clientLatencyMs));
        errorClasses=errorClasses==null ? Map.of() : Map.copyOf(errorClasses);
    }
    public record Requests(long sent,Map<String,Long> byEndpoint) {}
    public int exitCode() { return outcomes.getOrDefault("incomplete",0L)>0 ? 2 : 0; }
}
