package sg.edu.smu.cs203.market.price;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One run of the missing half-hour check (CSDT4-77); missingIntervals are interval starts, oldest first. */
public record PriceGapCheck(Instant checkedAt, Instant windowStart, Instant windowEnd, List<Instant> missingIntervals) {

    static final Duration HALF_HOUR = Duration.ofMinutes(30);

    public PriceGapCheck {
        missingIntervals = List.copyOf(missingIntervals);
    }

    /** Consecutive missing half-hours merged into [from, to) ranges. */
    public List<Range> ranges() {
        List<Range> ranges = new ArrayList<>();
        for (Instant start : missingIntervals) {
            Range last = ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
            if (last != null && last.to().equals(start)) {
                ranges.set(ranges.size() - 1, new Range(last.from(), start.plus(HALF_HOUR), last.halfHours() + 1));
            } else {
                ranges.add(new Range(start, start.plus(HALF_HOUR), 1));
            }
        }
        return ranges;
    }

    public record Range(Instant from, Instant to, int halfHours) {
    }
}
