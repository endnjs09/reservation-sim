package dev.endnjs.reservation.metrics;

import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class W2HistogramsTest {
    @Test void requestRatesAndWindowLengthIgnoreWallClockCorrections() {
        var nanos=new java.util.concurrent.atomic.AtomicLong();
        var clock=Clock.fixed(java.time.Instant.parse("2026-10-04T00:00:00Z"),java.time.ZoneOffset.UTC);
        var metrics=new MetricsCollector(clock,nanos::get);
        for(int i=0;i<100;i++) metrics.requestStarted("seats").finish(200);
        nanos.addAndGet(1_000_000_000L);
        var backwards=metrics.sample(clock.instant().minusSeconds(5));
        assertThat(backwards.total().rps()).isEqualTo(100);
        assertThat(backwards.endpoints().get("seats").rps()).isEqualTo(100);
        assertThat(backwards.windowMs()).isEqualTo(1000);
        for(int i=0;i<50;i++) metrics.requestStarted("holds").finish(409,"SEAT_UNAVAILABLE");
        nanos.addAndGet(500_000_000L);
        var forwards=metrics.sample(clock.instant().plusSeconds(3600));
        assertThat(forwards.total().rps()).isEqualTo(100);
        assertThat(forwards.windowMs()).isEqualTo(500);
        assertThat(forwards.total().errorClasses()).containsEntry("conflict",50L);
    }
    @Test void percentileWindowAndCumulativeMergeActualRequests() {
        var metrics=new RequestHistograms();metrics.register("empty");
        for(int i=0;i<100;i++) metrics.started("fast").finishElapsed(1_000_000,200,null);
        for(int i=0;i<100;i++) metrics.started("slow").finishElapsed(100_000_000,409,"SEAT_UNAVAILABLE");
        var first=metrics.sample(1);
        assertThat(first.total().rps()).isEqualTo(200);
        assertThat(first.total().latency().p95()).isCloseTo(100,within(.2));
        assertThat(first.endpoints().get("empty").latency().p95()).isNull();
        assertThat(first.total().errorClasses()).containsEntry("conflict",100L);
        var empty=metrics.sample(1);
        assertThat(empty.total().latency().p50()).isNull();
        assertThat(((Map<?,?>)empty.cumulative().get("total")).containsKey("count")).isTrue();
        assertThat(((Map<?,?>)empty.cumulative().get("total")).get("count")).isEqualTo(200L);
        assertThat(((Map<?,?>)empty.cumulative().get("total")).get("p95")).isEqualTo(first.total().latency().p95());
    }
    @Test void allErrorClassesHaveDefinedPrecedence() {
        assertThat(RequestHistograms.classify(409,"USER_ALREADY_PURCHASED")).isEqualTo("conflict");
        assertThat(RequestHistograms.classify(409,"SALE_ENDED")).isEqualTo("notPayable");
        assertThat(RequestHistograms.classify(403,"KEY_REVOKED")).isEqualTo("key");
        assertThat(RequestHistograms.classify(402,"DECLINED")).isEqualTo("declined");
        assertThat(RequestHistograms.classify(400,"INVALID_REQUEST")).isEqualTo("client");
        assertThat(RequestHistograms.classify(503,null)).isEqualTo("shed");
        assertThat(RequestHistograms.classify(502,null)).isEqualTo("server");
        assertThat(RequestHistograms.classify(0,"timeout")).isEqualTo("timeout");
        assertThat(RequestHistograms.classify(0,"transport")).isEqualTo("transport");
        assertThat(RequestHistograms.classify(200,null)).isNull();
    }
    @Test void inflightCompletionAcrossResetDoesNotContaminateNewEpoch() {
        var metrics=new MetricsCollector(Clock.systemUTC());var old=metrics.requestStarted("holds");metrics.reset();old.finish(409,"SEAT_UNAVAILABLE");
        var sample=metrics.sample(java.time.Instant.now());assertThat(sample.inflightTotal()).isZero();assertThat(sample.total().rps()).isZero();assertThat(sample.total().errorClasses().get("conflict")).isZero();
    }
    @Test void hikariTrackerRecordsAcquisitionAndTimeoutAndResets() {
        var metrics=new ResourceMetrics();var ds=new com.zaxxer.hikari.HikariDataSource();metrics.postProcessAfterInitialization(ds,"dataSource");
        var tracker=ds.getMetricsTrackerFactory().create("test",null);tracker.recordConnectionAcquiredNanos(12_000_000);tracker.recordConnectionTimeout();
        var sampled=metrics.acquisition();assertThat((Double)sampled.latency().get("p95")).isCloseTo(12,within(.1));assertThat(sampled.timeouts()).isEqualTo(1);
        metrics.reset();assertThat(metrics.acquisition().latency().get("p95")).isNull();assertThat(metrics.acquisition().timeouts()).isZero();ds.close();
    }
}
