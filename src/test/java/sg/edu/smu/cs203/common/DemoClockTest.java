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

        clock.startAt(START);
        assertThat(clock.instant()).isEqualTo(START);
        assertThat(clock.isRunning()).isTrue();

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
}
