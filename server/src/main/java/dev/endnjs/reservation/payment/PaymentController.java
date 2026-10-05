package dev.endnjs.reservation.payment;

import java.util.Map;
import java.util.UUID;
import dev.endnjs.reservation.hold.HoldController.UserRequest;
import dev.endnjs.reservation.hold.ReservationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private final CheckoutService checkout;
    private final ConfirmService confirms;
    private final ReservationQueryService reservations;
    public PaymentController(CheckoutService checkout, ConfirmService confirms, ReservationQueryService reservations) {
        this.checkout = checkout; this.confirms = confirms; this.reservations = reservations;
    }
    @PostMapping("/holds/{id}/checkout")
    CheckoutService.CheckoutResponse checkout(@PathVariable @Positive long id, @Valid @RequestBody UserRequest request,
            @RequestHeader(value = "X-Admission-Key", required = false) String token) {
        return checkout.checkout(id, request.userId(), token);
    }
    @PostMapping("/payments/confirm")
    ResponseEntity<Map<String, ReservationStatus>> confirm(@Valid @RequestBody ConfirmRequest request,
            @RequestHeader(value = "X-Admission-Key", required = false) String token) {
        var result = confirms.confirm(request.userId(), request.orderId(), request.paymentKey(), request.amount(), token);
        return ResponseEntity.status(result.httpStatus()).body(Map.of("status", result.status()));
    }
    @GetMapping("/reservations/{id}")
    ReservationQueryService.ReservationResponse reservation(@PathVariable @Positive long id) {
        return reservations.find(id);
    }
    public record ConfirmRequest(@NotBlank @Size(max = 64) String userId, @NotNull UUID orderId,
            @NotBlank @Size(max = 64) String paymentKey, @NotNull @PositiveOrZero Integer amount) {}
}
