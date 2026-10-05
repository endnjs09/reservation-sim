package dev.endnjs.simulator.engine;

import java.io.IOException;
import java.util.Map;

final class RunHttp {
    private final HttpTransport transport;
    private final RunControl control;
    private final RunStats stats;
    RunHttp(HttpTransport transport, RunControl control, RunStats stats) { this.transport = transport; this.control = control; this.stats = stats; }
    /** Service-internal lifecycle reads do not enter user request/response/RPS measurements. */
    HttpTransport.Response internalStats() throws IOException, InterruptedException {
        control.check();
        var response=transport.exchange(new HttpTransport.Request(HttpTransport.Target.SERVER,"GET","/admin/stats",
                Map.of(),null,java.time.Duration.ofMillis(Math.min(900,control.requestTimeout().toMillis()))));
        control.check();
        return response;
    }
    HttpTransport.Response internalReservation(long id) throws IOException,InterruptedException {
        var response=transport.exchange(new HttpTransport.Request(HttpTransport.Target.SERVER,"GET","/reservations/"+id,
                Map.of(),null,control.requestTimeout()));
        control.check();return response;
    }
    HttpTransport.Response send(String endpoint, HttpTransport.Target target, String method, String path,
            Map<String, String> headers, Map<String, Object> body) throws IOException, InterruptedException {
        var request = new HttpTransport.Request(target, method, path, headers, body, control.requestTimeout());
        boolean internal=path.startsWith("/admin/");
        var measurement = internal ? null : stats.request(endpoint,target);
        HttpTransport.Response response;
        try { response = transport.exchange(request); }
        catch (IOException | InterruptedException | RuntimeException failure) { if(measurement!=null) measurement.failed(failure); throw failure; }
        if(measurement!=null) measurement.response(response.status(),response.code());
        control.check();
        return response;
    }
}
