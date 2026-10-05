package dev.endnjs.simulator.cli;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dev.endnjs.simulator.engine.RunEngine;
import dev.endnjs.simulator.http.JdkHttpTransport;
import dev.endnjs.simulator.http.JsonCodec;

/** Standalone main; does not start a Spring application or HTTP server. */
public final class SimulatorCli {
    public static void main(String[] args) { System.exit(execute(args)); }
    public static int execute(String[] args) {
        Path configPath = Path.of("presets/default.json");
        Path output = Path.of("result.json");
        try {
            for (int i = 0; i < args.length; i++) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Expected --config <file> --out <file>");
                switch (args[i]) {
                    case "--config" -> configPath = Path.of(args[++i]);
                    case "--out" -> output = Path.of(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            var json = new JsonCodec();
            var config = json.readConfig(configPath);
            try (var transport = new JdkHttpTransport(config.targets(), json)) {
                dev.endnjs.simulator.engine.RunPreflight.check(config,transport);
                var engine = new RunEngine(config, transport);
                var saved = new CountDownLatch(1);
                var hook = new Thread(() -> {
                    engine.stop();
                    try { saved.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }, "cli-stop");
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    var summary = engine.run();
                    json.writeSummary(output, summary);
                    System.out.println("Summary saved: " + output.toAbsolutePath() + " " + summary.outcomes());
                    return summary.exitCode();
                } finally {
                    saved.countDown();
                    try { Runtime.getRuntime().removeShutdownHook(hook); }
                    catch (IllegalStateException shuttingDown) { /* shutdown hook is already running */ }
                }
            }
        } catch (Exception failure) {
            System.err.println("Simulator CLI: " + failure.getMessage());
            return 1;
        }
    }
}
