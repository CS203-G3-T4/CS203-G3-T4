package sg.edu.smu.cs203.market.price;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Finds half-hours missing from market_price (CSDT4-27/77). The window is the last N days up to
 * the start of the current half-hour, which has no price yet. It starts no earlier than the
 * half-hour of the first recorded poll, so time before the service ever ran is not a gap.
 */
@Service
public class PriceGapService {

    private static final Logger LOG = LoggerFactory.getLogger(PriceGapService.class);
    private static final long HALF_HOUR_SECONDS = PriceGapCheck.HALF_HOUR.toSeconds();

    private final PriceGapRepository gaps;
    private final IngestionRunRepository runs;
    private final Clock clock;
    private final Duration lookback;

    public PriceGapService(PriceGapRepository gaps, IngestionRunRepository runs, Clock clock,
            @Value("${market.gaps.days:7}") int days) {
        this.gaps = gaps;
        this.runs = runs;
        this.clock = clock;
        this.lookback = Duration.ofDays(days);
    }

    public PriceGapCheck check() {
        Instant now = clock.instant();
        Instant end = floorToHalfHour(now);
        Instant start = end.minus(lookback);
        Optional<Instant> firstPoll = runs.findFirstStartedAt().map(PriceGapService::floorToHalfHour);
        if (firstPoll.isPresent() && firstPoll.get().isAfter(start)) {
            start = firstPoll.get().isAfter(end) ? end : firstPoll.get();
        } else if (firstPoll.isEmpty()) {
            start = end;
        }
        List<Instant> missing = start.isBefore(end) ? gaps.findMissingIntervals(start, end) : List.of();
        PriceGapCheck check = new PriceGapCheck(now, start, end, missing);
        gaps.save(check);
        if (missing.isEmpty()) {
            LOG.info("No missing USEP half-hours between {} and {}", start, end);
        } else {
            LOG.warn("{} USEP half-hours missing between {} and {}: {}", missing.size(), start, end, check.ranges());
        }
        return check;
    }

    public Optional<PriceGapCheck> latest() {
        return gaps.findLatest();
    }

    static Instant floorToHalfHour(Instant instant) {
        long seconds = instant.getEpochSecond();
        return Instant.ofEpochSecond(seconds - Math.floorMod(seconds, HALF_HOUR_SECONDS));
    }
}
