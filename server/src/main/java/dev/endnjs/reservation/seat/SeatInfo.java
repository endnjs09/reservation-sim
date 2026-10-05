package dev.endnjs.reservation.seat;

public record SeatInfo(long id, String label, int rowIndex, int colIndex, Grade grade,
        int price, SeatStatus status) {}
