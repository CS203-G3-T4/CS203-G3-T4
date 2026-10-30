package sg.edu.smu.cs203.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A shared clock that uses real time until a demo instant is started.
 */
public final class DemoClock extends Clock {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    public record Snapshot(boolean running, Instant instant) {
    }

    private final Clock realClock;
    private final AtomicReference<Instant> simulatedNow;
    private final ZoneId zone;

    public DemoClock(Clock realClock) {
        this(Objects.requireNonNull(realClock, "realClock").withZone(SINGAPORE),
                new AtomicReference<>(),
                SINGAPORE);
    }

    private DemoClock(Clock realClock, AtomicReference<Instant> simulatedNow, ZoneId zone) {
        this.realClock = realClock;
        this.simulatedNow = simulatedNow;
        this.zone = zone;
    }

    @Override
    public Instant instant() {
        Instant simulated = simulatedNow.get();
        return simulated == null ? realClock.instant() : simulated;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        if (this.zone.equals(zone)) {
            return this;
        }
        return new DemoClock(realClock, simulatedNow, zone);
    }

    public void startAt(Instant start) {
        simulatedNow.set(Objects.requireNonNull(start, "start"));
    }

    public void advance(Duration step) {
        Objects.requireNonNull(step, "step");
        if (step.isNegative()) {
            throw new IllegalArgumentException("step must not be negative");
        }
        simulatedNow.updateAndGet(current -> {
            if (current == null) {
                throw new IllegalStateException("Cannot advance when the demo clock is stopped");
            }
            return current.plus(step);
        });
    }

    public void stop() {
        simulatedNow.set(null);
    }

    public boolean isRunning() {
        return simulatedNow.get() != null;
    }

    public Snapshot snapshot() {
        Instant simulated = simulatedNow.get();
        return simulated == null
                ? new Snapshot(false, realClock.instant())
                : new Snapshot(true, simulated);
    }
}
