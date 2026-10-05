package dev.endnjs.reservation.admission;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class KeyRegistry {
    private volatile Set<UUID> revoked=ConcurrentHashMap.newKeySet();
    public boolean revoked(UUID kid) { return revoked.contains(kid); }
    public void revoke(UUID kid) { if(kid!=null) revoked.add(kid); }
    public void reset() { revoked=ConcurrentHashMap.newKeySet(); }
}
