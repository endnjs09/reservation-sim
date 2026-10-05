package dev.endnjs.reservation.admission;

import dev.endnjs.reservation.sale.SaleService;
import dev.endnjs.reservation.snapshot.AvailabilityRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdmissionAccess {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AdmissionAccess.class);
    private final AdmissionKeyChecker checker;
    private final SaleService sale;
    private final AvailabilityRepository availability;
    private final SlotNotifier notifier;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public AdmissionAccess(AdmissionKeyChecker checker,SaleService sale,AvailabilityRepository availability,SlotNotifier notifier,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.checker=checker;this.sale=sale;this.availability=availability;this.notifier=notifier;
        transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    @Transactional public void check(String key,String uid) {
        sale.lockShared();
        if(checker.required()) {
            var target=AdmissionKeyFilter.CONTEXT.get();
            checker.check(key,new ProtectedRequest(uid,target==null ? null : target.reservationId()));
        }
    }
    public UUID keyId(String key) {
        if(!checker.required()) return null;
        var target=AdmissionKeyFilter.CONTEXT.get();
        return checker.check(key,target==null ? new ProtectedRequest(null,null) : target).kid();
    }
    /** Business transactions have returned their connection before this inventory refresh. */
    public void seatsChanged() {
        try {
            transactions.executeWithoutResult(tx -> {
                sale.lockShared();String epoch=sale.runEpoch();var summary=availability.read();
                if(summary.availableSeats()==0 && !sale.zeroObserved()) sale.recordZeroAvailability();
                notifier.saleState(summary.soldOut(),summary.releaseAt(),epoch);
            });
        } catch(RuntimeException failure) { log.warn("Inventory notification deferred to periodic refresh",failure); }
    }
}
