package dev.endnjs.reservation.deposit;

import dev.endnjs.reservation.hold.HoldController.UserRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
public class DepositController {
    private final DepositService deposits;
    public DepositController(DepositService deposits) { this.deposits=deposits; }
    @PostMapping("/holds/{id}/deposit")
    DepositService.Requested request(@PathVariable @Positive long id, @Valid @RequestBody UserRequest request,
            @RequestHeader(value="X-Admission-Key",required=false) String token) {
        return deposits.request(id,request.userId(),token);
    }
    @PostMapping("/deposits/{id}/pay")
    DepositService.Paid pay(@PathVariable @Positive long id, @Valid @RequestBody PayRequest request) {
        return deposits.pay(id,request.userId(),request.amount());
    }
    public record PayRequest(@NotBlank @Size(max=64) String userId,
            @jakarta.validation.constraints.NotNull @PositiveOrZero Integer amount) {}
}
