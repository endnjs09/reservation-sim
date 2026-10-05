package dev.endnjs.simulator.engine;

import dev.endnjs.simulator.http.JsonCodec;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class W2ClientMetricsTest {
    @Test void unavailablePgIsIdentifiedBeforeAnyResetAndUnusedQueueIsNeverCalled() {
        var calls=new ArrayList<HttpTransport.Request>();
        HttpTransport transport=r -> {
            calls.add(r);return new HttpTransport.Response(r.target()==HttpTransport.Target.PG ? 503 : 200,Map.of("status","UP"));
        };
        assertThatThrownBy(() -> RunPreflight.check(RunConfig.defaults(),transport)).isInstanceOfSatisfying(RunPreflight.TargetUnavailable.class,failure -> assertThat(failure.target()).isEqualTo(HttpTransport.Target.PG));
        assertThat(calls).hasSize(2).allSatisfy(r -> { assertThat(r.path()).isEqualTo("/actuator/health");assertThat(r.method()).isEqualTo("GET"); });
    }
    @Test void timeoutTransportAndServerFailureUseOnlyServerRequestDenominator() throws Exception {
        var config=new JsonCodec().config("{\"users\":1,\"timeScale\":1}");var time=RunTime.system();var control=new RunControl(time);control.start(30);
        var stats=new RunStats(config,time,List.of(Persona.FAST));
        HttpTransport transport=r -> {
            if(r.path().equals("/timeout")) throw new IOException("timeout",new java.util.concurrent.TimeoutException());
            if(r.path().equals("/offline")) throw new IOException("connection refused");
            return new HttpTransport.Response(r.path().equals("/bad") ? 503 : 409,Map.of("code","SEAT_UNAVAILABLE"));
        };
        var http=new RunHttp(transport,control,stats);
        for(String path:List.of("/timeout","/offline","/bad","/conflict")) {
            try { http.send("holds",HttpTransport.Target.SERVER,"POST",path,Map.of(),Map.of()); }catch(IOException expected) { }
        }
        try { http.send("auth",HttpTransport.Target.PG,"POST","/timeout",Map.of(),Map.of()); }catch(IOException expected) { }
        stats.sampleLive(true);var metrics=stats.clientMetrics();assertThat(metrics.serverRequests()).isEqualTo(4);assertThat(metrics.serverFailures()).isEqualTo(3);
        assertThat(metrics.histogram().total().errorClasses()).containsEntry("timeout",2L).containsEntry("transport",1L).containsEntry("shed",1L).containsEntry("conflict",1L);
        assertThat(metrics.serverFailed()*100.0/metrics.serverSent()).isEqualTo(75);
    }
    @Test void adminSetupNeverEntersUserHistogramAndTimeoutIsConfiguredInRealTime() throws Exception {
        var config=new JsonCodec().config("{\"users\":1,\"requestTimeoutMs\":2500}");var time=RunTime.system();var control=new RunControl(time);control.requestTimeoutMs(config.requestTimeoutMs());control.start(60,4);
        var stats=new RunStats(config,time,List.of(Persona.FAST));var http=new RunHttp(r -> { assertThat(r.timeout().toMillis()).isBetween(2490L,2500L);return new HttpTransport.Response(200,Map.of()); },control,stats);
        http.send("server.reset",HttpTransport.Target.SERVER,"POST","/admin/reset",Map.of(),Map.of());stats.sampleLive(true);
        assertThat(stats.summary().requests().sent()).isZero();assertThat(stats.clientMetrics().histogram().total().rps()).isZero();assertThat(stats.clientMetrics().serverSent()).isZero();
    }
}
