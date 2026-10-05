package dev.endnjs.reservation;

import dev.endnjs.reservation.admin.BackstopManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class V2MigrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("reservation").withUsername("reservation").withPassword("reservation");

    @Test void upgradesExistingActiveAndTerminalReservationsWithoutChangingCardData() {
        var datasource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(datasource);
        Flyway.configure().dataSource(datasource).target("1.1").load().migrate();
        var now = Timestamp.from(Instant.parse("2026-10-03T00:00:00Z"));
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO seats(id,label,row_index,col_index,grade,price,status,updated_at) "
                    + "VALUES (?,?,0,?,'VIP',150000,?,?)", i, "A" + i, i - 1, i == 3 ? "SOLD" : "HELD", now);
        }
        var statuses = List.of("HELD", "CONFIRMING", "CONFIRMED", "EXPIRED", "RELEASED", "PAYMENT_FAILED");
        for (int i = 0; i < statuses.size(); i++) {
            long id = jdbc.queryForObject("INSERT INTO reservations(seat_id,user_id,status,hold_expires_at,idempotency_key,created_at,updated_at) "
                    + "VALUES (?,?,?,?,?,?,?) RETURNING id", Long.class, i % 3 + 1, "u-" + i, statuses.get(i), now,
                    "key-" + i, now, now);
            if (i < 3) jdbc.update("UPDATE seats SET current_reservation_id=? WHERE id=?", id, i + 1);
        }
        UUID payment = UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id,reservation_id,amount,status,payment_key,created_at,updated_at) "
                + "VALUES (?,3,150000,'APPROVED','card',?,?)", payment, now, now);
        jdbc.execute("CREATE UNIQUE INDEX ux_res_seat_active ON reservations(seat_id) WHERE status IN ('HELD','CONFIRMING','CONFIRMED')");
        jdbc.execute("CREATE UNIQUE INDEX ux_res_user_active ON reservations(user_id) WHERE status IN ('HELD','CONFIRMING')");
        Flyway.configure().dataSource(datasource).load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE seat_count=1 AND payment_method='CARD'", Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_seats WHERE released_at IS NULL", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_seats WHERE released_at=?", Long.class, now)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations r JOIN reservation_seats rs ON rs.reservation_id=r.id AND rs.seat_id=r.seat_id", Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE current_reservation_id=id", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT amount FROM payments WHERE id=?", Integer.class, payment)).isEqualTo(150000);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, payment)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname='ux_res_seat_active'", Long.class)).isZero();
        var backstop = new BackstopManager(jdbc);
        backstop.apply(true);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO reservation_seats(reservation_id,seat_id) VALUES (4,2)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        backstop.apply(false);
        jdbc.update("INSERT INTO reservation_seats(reservation_id,seat_id,released_at) VALUES (4,2,?)", now);
        jdbc.update("UPDATE seats SET status='PENDING_DEPOSIT' WHERE id=1");
        jdbc.update("UPDATE seats SET status='RETURN_PENDING' WHERE id=2");
        jdbc.update("UPDATE reservations SET status='PENDING_DEPOSIT',payment_method='DEPOSIT',deposit_deadline=? WHERE id=1", now);
        jdbc.update("UPDATE reservations SET status='DEPOSIT_EXPIRED' WHERE id=2");
        jdbc.update("UPDATE reservations SET status='CANCELED' WHERE id=3");
        backstop.apply(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('ux_rs_seat_active','ux_res_user_active')", Long.class)).isEqualTo(2);
    }
}
