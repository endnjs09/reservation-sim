package dev.endnjs.simulator;

import dev.endnjs.simulator.http.JsonCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Fake GET /seats bodies built from contracts/seats-response.json.
 * server V3IntegrationTest checks that file's field set against the real /seats response (Testcontainers),
 * so a fake built here has the same shape as the real server.
 */
public final class SeatsContract {
    public static final Path FILE=Path.of("../contracts/seats-response.json");
    private static final Map<String,Object> FIXTURE=load();
    private SeatsContract() {}

    public static Map<String,Object> seat(long id,String label,String status) {
        return Map.of("id",id,"label",label,"grade","VIP","status",status);
    }
    /** Inventory fields follow the seat statuses; saleEndAt/releaseAt/phase keep the fixture values unless overridden. */
    public static Map<String,Object> response(int cols,List<Map<String,Object>> seats) {
        var body=new LinkedHashMap<>(FIXTURE);
        body.put("cols",cols);body.put("rows",Math.max(1,(seats.size()+cols-1)/cols));body.put("seats",seats);
        long available=count(seats,"AVAILABLE"),held=count(seats,"HELD");
        body.put("availableSeats",available);body.put("heldSeats",held);
        body.put("pendingDepositSeats",count(seats,"PENDING_DEPOSIT"));body.put("returnPendingSeats",count(seats,"RETURN_PENDING"));
        body.put("soldOut",available==0 && held==0);
        return Collections.unmodifiableMap(body);
    }
    public static Map<String,Object> with(Map<String,Object> response,String key,Object value) {
        if(!FIXTURE.containsKey(key)) throw new IllegalArgumentException("Not a /seats field: "+key);
        var body=new LinkedHashMap<>(response);body.put(key,value);return Collections.unmodifiableMap(body);
    }
    public static Set<String> fields() { return FIXTURE.keySet(); }
    @SuppressWarnings("unchecked")
    public static Set<String> seatFields() { return ((List<Map<String,Object>>)FIXTURE.get("seats")).getFirst().keySet(); }

    /** 429 RATE_LIMITED 본문 (contracts/seats-rate-limited.json, 서버 SeatsRefreshIntegrationTest가 실제 응답과 필드를 맞춰 봄). */
    @SuppressWarnings("unchecked")
    public static Map<String,Object> rateLimited(long retryAfterMs) {
        try {
            var body=new LinkedHashMap<>((Map<String,Object>)new JsonCodec().decode(Files.readString(Path.of("../contracts/seats-rate-limited.json"))));
            body.put("retryAfterMs",retryAfterMs);return Collections.unmodifiableMap(body);
        } catch(IOException missing) { throw new UncheckedIOException(missing); }
    }
    private static long count(List<Map<String,Object>> seats,String status) { return seats.stream().filter(s -> status.equals(s.get("status"))).count(); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> load() {
        try { return Collections.unmodifiableMap((Map<String,Object>)new JsonCodec().decode(Files.readString(FILE))); }
        catch(IOException missing) { throw new UncheckedIOException(missing); }
    }
}
