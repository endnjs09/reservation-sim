package dev.endnjs.reservation.hold;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import dev.endnjs.reservation.common.ApiException;
import dev.endnjs.reservation.common.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class HoldController {
    private final HoldService holds;
    public HoldController(HoldService holds) { this.holds = holds; }

    @PostMapping("/holds")
    ResponseEntity<HoldResponse> hold(@Valid @RequestBody HoldRequest request,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 64) String key,
            @RequestHeader(value = "X-Admission-Key", required = false) String token) {
        if ((request.seatId() == null) == (request.seatIds() == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Specify either seatId or seatIds");
        }
        return ResponseEntity.status(201).body(holds.hold(request.userId(),
                request.seatIds() == null ? List.of(request.seatId()) : request.seatIds(), key, token));
    }

    @PostMapping("/holds/{id}/release")
    ReleaseResponse release(@PathVariable @Positive long id, @Valid @RequestBody UserRequest request,
            @RequestHeader(value = "X-Admission-Key", required = false) String token) {
        return new ReleaseResponse(holds.release(id, request.userId(), token));
    }

    public record HoldRequest(@NotBlank @Size(max = 64) String userId, @Positive Long seatId,
            List<@NotNull @Positive Long> seatIds) {}
    public record UserRequest(@NotBlank @Size(max = 64) String userId) {}
    public record ReleaseResponse(ReservationStatus status) {}
}
