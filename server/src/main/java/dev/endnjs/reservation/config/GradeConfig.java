package dev.endnjs.reservation.config;

import dev.endnjs.reservation.seat.Grade;

public record GradeConfig(Grade name, int rows, int price) {}
