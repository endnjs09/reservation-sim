package dev.endnjs.reservation.seat;

public record SeatView(long id, String label, Grade grade, int price) {
    public static SeatView from(SeatInfo seat) {
        return new SeatView(seat.id(), seat.label(), seat.grade(), seat.price());
    }
}
