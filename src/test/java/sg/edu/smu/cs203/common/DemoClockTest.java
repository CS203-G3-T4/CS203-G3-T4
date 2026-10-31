package sg.edu.smu.cs203.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DemoClockTest {

    private static final Instant REAL_NOW = Instant.parse("2026-10-24T08:00:00Z");
    private static final Instant START = Instant.parse("2026-09-26T00:00:00Z");
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private final DemoClock clock = new DemoClock(Clock.fixed(REAL_NOW, SINGAPORE));

    @Test
    void usesInjectedRealTimeUntilStartedAndAfterStopped() {
        assertThat(clock.instant()).isEqualTo(REAL_NOW);
        assertThat(clock.isRunning()).isFalse();
        assertThat(clock.snapshot()).isEqualTo(new DemoClock.Snapshot(false, REAL_NOW));

        clock.startAt(START);
        assertThat(clock.instant()).isEqualTo(START);
        assertThat(clock.isRunning()).isTrue();
        assertThat(clock.snapshot()).isEqualTo(new DemoClock.Snapshot(true, START));

        clock.stop();
        assertThat(clock.instant()).isEqualTo(REAL_NOW);
        assertThat(clock.isRunning()).isFalse();
    }

    @Test
    void advancesSimulatedTimeAtomicallyByTheRequestedDuration() {
        clock.startAt(START);

        clock.advance(Duration.ofMinutes(30));
        clock.advance(Duration.ofHours(2));

        assertThat(clock.instant()).isEqualTo(START.plus(Duration.ofMinutes(150)));
    }

    @Test
    void zoneViewsShareTheSameSimulatedInstantAndReportTheirOwnZone() {
        clock.startAt(START);

        Clock tokyoClock = clock.withZone(ZoneId.of("Asia/Tokyo"));
        assertThat(tokyoClock.getZone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(tokyoClock.instant()).isEqualTo(START);

        clock.advance(Duration.ofMinutes(30));
        assertThat(tokyoClock.instant()).isEqualTo(START.plus(Duration.ofMinutes(30)));
        Clock singaporeClock = tokyoClock.withZone(SINGAPORE);
        assertThat(singaporeClock.getZone()).isEqualTo(SINGAPORE);
        assertThat(singaporeClock.instant()).isEqualTo(clock.instant());
    }

    @Test
    void rejectsInvalidLifecycleInputs() {
        assertThatThrownBy(() -> clock.startAt(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("start");
        assertThatThrownBy(() -> clock.advance(Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped");

        clock.startAt(START);
        assertThatThrownBy(() -> clock.advance(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("step");
        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThat(clock.instant()).isEqualTo(START);
    }

    @Test
    void stagesTimeUntilSuccessfulReturnAndDiscardsItOnFailure() throws Exception {
        var otherZone=clock.withZone(ZoneId.of("UTC"));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            clock.transition(() -> {
                clock.startAt(START);
                assertThat(otherZone.instant()).isEqualTo(START);
                try { assertThat(pool.submit(clock::snapshot).get()).isEqualTo(new DemoClock.Snapshot(false,REAL_NOW)); }
                catch(Exception e) { throw new RuntimeException(e); }
                return null;
            });
            assertThat(clock.instant()).isEqualTo(START);
            assertThatThrownBy(() -> clock.transition(() -> {
                clock.advance(Duration.ofMinutes(30));
                throw new IllegalStateException("transaction commit failed");
            })).hasMessage("transaction commit failed");
            assertThat(clock.instant()).isEqualTo(START);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void concurrentAdvanceAndStopCannotRestoreStaleState() throws Exception {
        clock.startAt(START);
        var staged=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var stopEntered=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var failed=pool.submit(() -> clock.transition(() -> {
                clock.advance(Duration.ofMinutes(30)); staged.countDown();
                try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
                throw new IllegalStateException("refresh failed");
            }));
            assertThat(staged.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var stopped=pool.submit(() -> { stopEntered.countDown(); return clock.transition(() -> { clock.stop(); return clock.snapshot(); }); });
            assertThat(stopEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(clock.instant()).isEqualTo(START);
            release.countDown();
            assertThatThrownBy(() -> failed.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(stopped.get(5,java.util.concurrent.TimeUnit.SECONDS).running()).isFalse();
            assertThat(clock.instant()).isEqualTo(REAL_NOW);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
