package dev.endnjs.reservation.hold;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import dev.endnjs.reservation.seat.SeatView;
import dev.endnjs.reservation.seat.SeatStatus;
import java.util.stream.Collectors;
import jakarta.persistence.OptimisticLockException;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import dev.endnjs.reservation.config.RuntimeConfigStore;
import dev.endnjs.reservation.metrics.MetricsCollector;
import dev.endnjs.reservation.admission.AdmissionAccess;
import dev.endnjs.reservation.sale.SaleService;
import dev.endnjs.reservation.seat.SeatRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HoldService {
    private final ReservationRepository reservations;
    private final SeatRepository seats;
    private final RuntimeConfigStore configs;
    private final MetricsCollector metrics;
    private final AdmissionAccess queue;
    private final Clock clock;
    private final Map<String, HoldStrategy> strategies;
    private final TransactionTemplate transactions;
    private final SaleService sale;

    public HoldService(ReservationRepository reservations, SeatRepository seats, RuntimeConfigStore configs,
            MetricsCollector metrics, AdmissionAccess queue, Clock clock, List<HoldStrategy> strategies,
            SaleService sale, PlatformTransactionManager transactionManager) {
        this.reservations = reservations; this.seats = seats; this.configs = configs;
        this.metrics = metrics; this.queue = queue; this.clock = clock;
        this.sale = sale;
        this.strategies = strategies.stream().collect(Collectors.toUnmodifiableMap(HoldStrategy::name, s -> s));
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public HoldResponse hold(String userId, long seatId, String key, String token) {
        return hold(userId, List.of(seatId), key, token);
    }

    public HoldResponse hold(String userId, List<Long> requested, String key, String token) {
        if (requested == null || requested.isEmpty() || requested.size() > configs.current().maxSeatsPerUser()
                || requested.stream().anyMatch(id -> id == null || id <= 0)
                || requested.stream().distinct().count() != requested.size()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid seatIds");
        }
        List<Long> seatIds = requested.stream().sorted().toList();
        metrics.increment("holdAttempts");
        try {
            Attempt result;
            try {
                result = transactions.execute(status -> acquire(userId, seatIds, key, token));
            } catch (DataIntegrityViolationException conflict) {
                if (hasConstraint(conflict, "ux_reservations_idempotency")) {
                    // The failed transaction has rolled back before the existing row is read.
                    result = transactions.execute(status -> {
                        queue.check(token, userId);
                        var existing = reservations.findByKey(key).orElseThrow(() -> conflict);
                        return new Attempt(replay(existing, userId, seatIds), false);
                    });
                } else if (hasConstraint(conflict, "ux_rs_seat_active")) {
                    throw unavailable(seatIds, constraintSeatIds(conflict));
                } else if (hasConstraint(conflict, "ux_res_user_active")) {
                    throw new ApiException(ErrorCode.USER_ALREADY_HOLDING, "User already holds a seat");
                } else {
                    throw conflict;
                }
            } catch (OptimisticLockException | OptimisticLockingFailureException conflict) {
                throw unavailable(seatIds, List.of());
            }
            if (result.created()) {
                metrics.increment("holdSuccess");
                queue.seatsChanged();
            }
            return result.response();
        } catch (ApiException exception) {
            if (exception.code() == ErrorCode.SEAT_UNAVAILABLE
                    || exception.code() == ErrorCode.USER_ALREADY_HOLDING
                    || exception.code() == ErrorCode.USER_ALREADY_PURCHASED) {
                // In naive mode another request can commit between the initial key lookup and validation.
                // Resolve a committed identical key without retrying seat acquisition.
                HoldResponse committed = transactions.execute(status -> {
                    queue.check(token, userId);
                    return reservations.findByKey(key).map(r -> replay(r, userId, seatIds)).orElse(null);
                });
                if (committed != null) return committed;
            }
            if (exception.code() == ErrorCode.SEAT_UNAVAILABLE) {
                var failure = unavailable(seatIds, exception instanceof SeatUnavailableException detailed
                        ? detailed.unavailableSeatIds() : List.of());
                failure.unavailableSeatIds().forEach(metrics::conflict);
                throw failure;
            }
            throw exception;
        }
    }

    private Attempt acquire(String userId, List<Long> seatIds, String key, String token) {
        sale.requireOpen();
        queue.check(token, userId);
        var existing = reservations.findByKey(key);
        if (existing.isPresent()) return new Attempt(replay(existing.get(), userId, seatIds), false);
        var config = configs.current();
        if (!config.strategy().equals("naive")) {
            reservations.lockUser(userId);
            // A concurrent request may have committed the same key while this one waited for the user lock.
            existing = reservations.findByKey(key);
            if (existing.isPresent()) return new Attempt(replay(existing.get(), userId, seatIds), false);
        }
        var userStatuses = reservations.activeUserStatuses(userId);
        if (userStatuses.contains(ReservationStatus.CONFIRMED) || userStatuses.contains(ReservationStatus.PENDING_DEPOSIT)) {
            throw new ApiException(ErrorCode.USER_ALREADY_PURCHASED, "User already purchased a seat");
        }
        if (!userStatuses.isEmpty()) {
            throw new ApiException(ErrorCode.USER_ALREADY_HOLDING, "User already holds a seat");
        }
        var requestedSeats = seats.find(seatIds);
        if (requestedSeats.size() != seatIds.size()) throw new ApiException(ErrorCode.SEAT_NOT_FOUND, "Seat not found");
        if (requestedSeats.stream().mapToLong(s -> s.price()).sum() > Integer.MAX_VALUE) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Total price exceeds the supported amount");
        }
        var now = clock.instant();
        strategies.get(config.strategy()).acquire(seatIds, now);
        sale.requireOpen(); // Recheck after waiting for user/seat locks.
        var reservation = reservations.create(userId, seatIds, key, now, now.plus(config.realDuration(config.holdTtlSec())),queue.keyId(token));
        seats.attachReservation(seatIds, reservation.id());
        return new Attempt(response(reservation), true);
    }

    private HoldResponse replay(Reservation reservation, String userId, List<Long> seatIds) {
        if (!reservation.userId().equals(userId) || !reservations.seatIds(reservation.id()).equals(seatIds)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Key was used with different content");
        }
        return response(reservation);
    }

    private HoldResponse response(Reservation reservation) {
        var all = seats.forReservation(reservation.id());
        var representative = all.getFirst();
        return new HoldResponse(reservation.id(), representative.id(), representative.grade(), representative.price(),
                ReservationStatus.HELD, reservation.holdExpiresAt(), all.stream().map(SeatView::from).toList(),
                all.stream().mapToInt(s -> s.price()).sum());
    }

    private SeatUnavailableException unavailable(List<Long> requested, List<Long> known) {
        // Called after rollback: seats changed by our failed attempt must not appear as conflicts.
        var occupied = seats.find(requested).stream().filter(s -> s.status() != SeatStatus.AVAILABLE).map(s -> s.id());
        var ids = java.util.stream.Stream.concat(known.stream(), occupied).distinct().sorted().toList();
        return new SeatUnavailableException(ids);
    }

    public ReservationStatus release(long id, String userId, String token) {
        ReleaseResult result = transactions.execute(status -> {
            queue.check(token, userId);
            var reservation = reservations.lock(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found"));
            if (!reservation.userId().equals(userId)) {
                throw new ApiException(ErrorCode.RESERVATION_NOT_PAYABLE, "Reservation belongs to another user");
            }
            if (reservation.status() != ReservationStatus.HELD) return new ReleaseResult(reservation.status(),0);
            var now = clock.instant();
            reservations.release(id, now);
            int returned=seats.releaseReservation(id, now);
            return new ReleaseResult(ReservationStatus.RELEASED,returned);
        });
        metrics.add("immediateReturns",result.returned());
        if (result.status() == ReservationStatus.RELEASED) queue.seatsChanged();
        return result.status();
    }

    private static List<Long> constraintSeatIds(Throwable exception) {
        var pattern = java.util.regex.Pattern.compile("Key \\(seat_id\\)=\\((\\d+)\\)");
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() == null) continue;
            var match = pattern.matcher(cause.getMessage());
            if (match.find()) return List.of(Long.parseLong(match.group(1)));
        }
        return List.of();
    }
    private static boolean hasConstraint(Throwable exception, String name) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains('"' + name + '"')) return true;
        }
        return false;
    }
    private record ReleaseResult(ReservationStatus status,int returned) {}
    private record Attempt(HoldResponse response, boolean created) {}
}
