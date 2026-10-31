package sg.edu.smu.cs203.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Shared demo time. A controller transition publishes only after its database work commits. */
public final class DemoClock extends Clock {
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    public record Snapshot(boolean running, Instant instant) {}
    private static final class State {
        final AtomicReference<Instant> published = new AtomicReference<>();
        final ThreadLocal<Snapshot> staged = new ThreadLocal<>();
        final ReentrantLock operations = new ReentrantLock();
    }
    private final Clock realClock;
    private final State state;
    private final ZoneId zone;
    public DemoClock(Clock realClock) {
        this(Objects.requireNonNull(realClock, "realClock").withZone(SINGAPORE), new State(), SINGAPORE);
    }
    private DemoClock(Clock realClock, State state, ZoneId zone) {
        this.realClock=realClock; this.state=state; this.zone=zone;
    }
    @Override public Instant instant() { return snapshot().instant(); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        return this.zone.equals(zone) ? this : new DemoClock(realClock,state,zone);
    }
    public Snapshot snapshot() {
        Snapshot staged=state.staged.get();
        Instant simulated=staged==null ? state.published.get() : staged.running() ? staged.instant() : null;
        return simulated==null ? new Snapshot(false,realClock.instant()) : new Snapshot(true,simulated);
    }
    public boolean isRunning() { return snapshot().running(); }
    private void set(Instant instant) {
        if(state.staged.get()!=null) state.staged.set(new Snapshot(instant!=null,instant));
        else state.published.set(instant);
    }
    public void startAt(Instant start) {
        Objects.requireNonNull(start,"start");
        state.operations.lock();
        try { set(start); } finally { state.operations.unlock(); }
    }
    public void advance(Duration step) {
        Objects.requireNonNull(step,"step");
        if(step.isNegative()) throw new IllegalArgumentException("step must not be negative");
        state.operations.lock();
        try {
            Snapshot current=snapshot();
            if(!current.running()) throw new IllegalStateException("Cannot advance when the demo clock is stopped");
            set(current.instant().plus(step));
        } finally { state.operations.unlock(); }
    }
    public void stop() {
        state.operations.lock();
        try { set(null); } finally { state.operations.unlock(); }
    }
    /**
     * Serializes admin operations. The operation's thread sees staged time; other threads keep
     * seeing the last committed clock. Supply a call through the transactional replay service
     * proxy, so its commit (and commit failure) occurs before publishing. No database lock is
     * held while another thread waits to read the clock. Failure discards the staged clock.
     */
    public <T> T transition(Supplier<T> operation) {
        state.operations.lock();
        try {
            if(state.staged.get()!=null) throw new IllegalStateException("Nested demo clock transition");
            state.staged.set(snapshot());
            try {
                T result=operation.get();
                Snapshot next=state.staged.get();
                state.published.set(next.running()?next.instant():null);
                return result;
            } finally { state.staged.remove(); }
        } finally { state.operations.unlock(); }
    }
}
