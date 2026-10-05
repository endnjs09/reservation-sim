package dev.endnjs.simulator.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/** Shared web/CLI health preflight, performed before any reset or run record creation. */
public final class RunPreflight {
    private RunPreflight() {}
    public static final class TargetUnavailable extends RuntimeException {
        private final HttpTransport.Target target;
        public TargetUnavailable(HttpTransport.Target target,Throwable cause) { super("Target down: "+target,cause);this.target=target; }
        public HttpTransport.Target target() { return target; }
    }
    public static void check(RunConfig config,HttpTransport transport) {
        for(var target:List.of(HttpTransport.Target.SERVER,HttpTransport.Target.PG,HttpTransport.Target.QUEUE)) {
            try {
                var response=transport.exchange(new HttpTransport.Request(target,"GET","/actuator/health",Map.of(),null,Duration.ofMillis(Math.min(3000,config.requestTimeoutMs()))));
                if(!response.ok() || !"UP".equals(response.body().get("status"))) throw new IOException("Unhealthy target");
            } catch(IOException|InterruptedException|RuntimeException failure) {
                if(failure instanceof InterruptedException) Thread.currentThread().interrupt();throw new TargetUnavailable(target,failure);
            }
        }
    }
}
