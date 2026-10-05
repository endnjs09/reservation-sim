package dev.endnjs.reservation.admin;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import dev.endnjs.reservation.config.RuntimeConfigStore;
import dev.endnjs.reservation.hold.ReservationStatus;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.snapshot.AvailabilityRepository;
import dev.endnjs.reservation.seat.SeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {
    private final JdbcTemplate jdbc;
    private final dev.endnjs.reservation.admission.SlotNotifier notifier;
    private final dev.endnjs.reservation.sale.SaleService sale;
    private final dev.endnjs.reservation.resale.ReleaseBatchRepository batches;
    private final RuntimeConfigStore configs;
    private final MetricsCollector metrics;
    private final Clock clock;
    private final AvailabilityRepository availability;
    private final dev.endnjs.reservation.snapshot.SnapshotLocks locks;
    public StatsService(JdbcTemplate jdbc, RuntimeConfigStore configs, MetricsCollector metrics, Clock clock, AvailabilityRepository availability, dev.endnjs.reservation.resale.ReleaseBatchRepository batches, dev.endnjs.reservation.snapshot.SnapshotLocks locks,dev.endnjs.reservation.sale.SaleService sale,dev.endnjs.reservation.admission.SlotNotifier notifier) {
        this.sale=sale;this.notifier=notifier;this.locks=locks; this.jdbc = jdbc; this.batches=batches; this.configs = configs; this.metrics = metrics; this.clock = clock; this.availability = availability;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> stats() {
        locks.read();
        var config = configs.current();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverTime", clock.instant());
        result.put("config", config);result.put("runEpoch",sale.runEpoch());
        Map<String, Object> seats = new LinkedHashMap<>(counts("seats", names(SeatStatus.values())));
        seats.put("total", jdbc.queryForObject("SELECT count(*) FROM seats", Long.class));
        Map<String, Map<String, Long>> byGrade = new LinkedHashMap<>();
        config.grades().forEach(g -> byGrade.put(g.name().name(), zeroCounts(names(SeatStatus.values()))));
        jdbc.query("SELECT grade,status,count(*) AS n FROM seats GROUP BY grade,status", rs -> {
            byGrade.computeIfAbsent(rs.getString("grade"), ignored -> zeroCounts(names(SeatStatus.values())))
                    .put(rs.getString("status"), rs.getLong("n"));
        });
        seats.put("byGrade", byGrade);
        result.put("seats", seats);
        result.put("reservations", counts("reservations", names(ReservationStatus.values())));
        result.put("payments", counts("payments", new String[]{"REQUESTED", "APPROVED", "FAILED", "CANCELED"}));
        var batchSummary=batches.summary();
        Map<String,Object> releaseBatches=new LinkedHashMap<>();
        releaseBatches.put("open",batchSummary.open());
        releaseBatches.put("nextReleaseAt",batchSummary.nextReleaseAt());
        releaseBatches.put("totalReleased",batchSummary.totalReleased());
        result.put("releaseBatches",releaseBatches);
        result.putAll(availability.read().fields(clock.instant()));
        result.put("counters", metrics.counters());
        return result;
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> snapshot() {
        locks.read();var result=new LinkedHashMap<String,Object>();
        for(String table: java.util.List.of("seats","reservations","reservation_seats","payments","release_batches")) {
            result.put(table,jdbc.query("SELECT * FROM "+table+" ORDER BY "+(table.equals("reservation_seats") ? "reservation_id,seat_id" : "id"),(rs,row) -> {
                var values=new LinkedHashMap<String,Object>();var metadata=rs.getMetaData();
                for(int i=1;i<=metadata.getColumnCount();i++) {
                    Object v=rs.getObject(i);if(v instanceof java.sql.Timestamp ts) v=ts.toInstant().toString();if(v instanceof java.util.UUID uuid) v=uuid.toString();
                    values.put(metadata.getColumnLabel(i),v);
                }return values;
            }));
        }
        result.put("counters",metrics.counters());result.put("runEpoch",sale.runEpoch());result.put("droppedNotifications",notifier.droppedNotifications());return result;
    }
    private Map<String, Long> counts(String table, String[] statuses) {
        Map<String, Long> counts = zeroCounts(statuses);
        // Table names come only from the fixed calls above, never from request input.
        jdbc.query("SELECT status,count(*) AS n FROM " + table + " GROUP BY status",
                rs -> { counts.put(rs.getString("status"), rs.getLong("n")); });
        return counts;
    }
    private static Map<String, Long> zeroCounts(String[] statuses) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : statuses) counts.put(status, 0L);
        return counts;
    }
    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
