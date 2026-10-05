package dev.endnjs.simulator.cli;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import dev.endnjs.simulator.http.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class S5CliTest {
    @TempDir Path temp;
    @Test void standaloneCliReturnsZeroAndWritesSummaryWithoutSpring() throws Exception { check(false,0,"soldOut"); }
    @Test void standaloneCliReturnsTwoAndStillWritesIncompleteSummary() throws Exception { check(true,2,"incomplete"); }
    private void check(boolean waiting,int exit,String outcome) throws Exception {
        var json = new JsonCodec();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                Map<String,Object> body = switch (path) {
                    case "/actuator/health" -> Map.of("status","UP");
                    case "/queue/enter" -> Map.of("token","2d7b8990-1be4-4ad2-a4dc-58d2c8a965ea","status",waiting ? "WAITING" : "CLOSED","pollAfterMs",1000,"reason","SALE_ENDED");
                    case "/seats" -> dev.endnjs.simulator.SeatsContract.response(1,java.util.List.of(dev.endnjs.simulator.SeatsContract.seat(1,"A1","SOLD")));
                    default -> Map.of();
                };
                byte[] response = json.encode(body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                exchange.close();
            });
            server.start();
            String url = "http://127.0.0.1:"+server.getAddress().getPort();
            Path input = temp.resolve("config.json"), output = temp.resolve("output/summary.json"), log = temp.resolve("cli.log");
            Files.writeString(input,json.encode(Map.of("users",1,"timeLimitSec",waiting ? 1 : 20,
                    "arrival",java.util.List.of(Map.of("percent",100,"fromSec",0,"toSec",0)),"targets",Map.of("server",url,"pg",url,"queue",url))));
            Process cli = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                    "-cp",System.getProperty("simulator.test.classpath"),SimulatorCli.class.getName(),
                    "--config",input.toString(),"--out",output.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertThat(cli.waitFor(10,TimeUnit.SECONDS)).isTrue();
                assertThat(cli.exitValue()).describedAs(Files.readString(log)).isEqualTo(exit);
                var summary = json.response(Files.readString(output));
                assertThat(((Map<?,?>) summary.get("outcomes")).get(outcome)).isEqualTo(1);
                assertThat(summary).containsKeys("startedAt","durationMs","config","requests","responses","avgLatencyMs","outcomesByPersona","events");
                assertThat(Files.readString(log)).contains("Summary saved:").doesNotContain("Spring Boot","Starting SimulatorApplication");
            } finally { if (cli.isAlive()) cli.destroyForcibly(); server.stop(0); }
        }
    }
}
