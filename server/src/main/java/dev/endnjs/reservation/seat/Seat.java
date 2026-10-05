package dev.endnjs.reservation.seat;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "seats")
public class Seat {
    @Id private Long id;
    @Column(nullable = false, length = 10) private String label;
    @Column(name = "row_index", nullable = false) private int rowIndex;
    @Column(name = "col_index", nullable = false) private int colIndex;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Grade grade;
    @Column(nullable = false) private int price;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private SeatStatus status;
    @Column(name = "current_reservation_id") private Long currentReservationId;
    @Version @Column(nullable = false) private long version;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Seat() {}
    public SeatStatus status() { return status; }
    public void hold(Instant now) { status = SeatStatus.HELD; updatedAt = now; }
}
