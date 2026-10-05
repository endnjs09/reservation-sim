package dev.endnjs.reservation.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BackstopManager {
    private final JdbcTemplate jdbc;
    public BackstopManager(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void apply(boolean enabled) {
        jdbc.execute("DROP INDEX IF EXISTS ux_res_seat_active");
        if (enabled) {
            jdbc.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_rs_seat_active ON reservation_seats(seat_id)
                    WHERE released_at IS NULL
                    """);
            jdbc.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_res_user_active ON reservations(user_id)
                    WHERE status IN ('HELD','CONFIRMING')
                    """);
        } else {
            jdbc.execute("DROP INDEX IF EXISTS ux_rs_seat_active");
            jdbc.execute("DROP INDEX IF EXISTS ux_res_user_active");
        }
    }
}
