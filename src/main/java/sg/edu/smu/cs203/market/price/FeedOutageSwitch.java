package sg.edu.smu.cs203.market.price;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Simulated NEMS outage for demos and tests (CSDT4-26). While on, UsepClient fails every fetch
 * without calling NEMS. Held in memory only, so a restart always turns it off.
 */
@Component
public class FeedOutageSwitch {

    private final AtomicReference<Instant> onSince = new AtomicReference<>();

    public void turnOn(Instant now) {
        onSince.compareAndSet(null, now);
    }

    public void turnOff() {
        onSince.set(null);
    }

    public Optional<Instant> onSince() {
        return Optional.ofNullable(onSince.get());
    }

    public boolean isOn() {
        return onSince.get() != null;
    }
}
