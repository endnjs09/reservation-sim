package dev.endnjs.reservation.payment;

import dev.endnjs.reservation.hold.HoldController.UserRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

@RestController
public class CancellationController {
    private final CancellationService cancellations;
    public CancellationController(CancellationService cancellations) { this.cancellations=cancellations; }
    @PostMapping("/reservations/{id}/cancel")
    CancellationService.Canceled cancel(@PathVariable @Positive long id,@Valid @RequestBody UserRequest request) {
        return cancellations.cancel(id,request.userId());
    }
}
