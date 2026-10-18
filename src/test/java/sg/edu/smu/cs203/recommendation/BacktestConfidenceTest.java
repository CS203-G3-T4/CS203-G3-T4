package sg.edu.smu.cs203.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.recommendation.BacktestConfidence.Confidence;
import sg.edu.smu.cs203.recommendation.CheapestStartCalculator.ShiftableRun;

/** CSDT4-39 option A: how often the suggested time really was cheaper on past days. Fixed times. */
class BacktestConfidenceTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private final Instant now = sg("2026-09-30T08:10");
    private final ShiftableRun dryer = new ShiftableRun(new BigDecimal("2.5"), 60,
            LocalTime.of(7, 0), LocalTime.of(21, 0), LocalTime.of(19, 45));
    private final Instant usual = sg("2026-09-30T19:45");
    private final Instant suggested = sg("2026-09-30T13:00");

    @Test
    void countsTheDaysTheSuggestedTimeWasReallyCheaper() {
        // Every one of the last 28 days has prices; 13:00 was cheaper on 21 of them (not on days 1-7).
        // Day 1 (29 Sep) is finished by now, so all 28 days count.
        Map<Instant, BigDecimal> prices = pastPrices(28, Set.of(1, 2, 3, 4, 5, 6, 7), Set.of());

        Confidence confidence = BacktestConfidence.of(dryer, usual, suggested, prices, now);

        assertThat(confidence).isEqualTo(new Confidence(21, 28));
        assertThat(confidence.percent()).isEqualTo(75);
    }

    @Test
    void daysWithAMissingPriceAreSkippedNotGuessed() {
        Map<Instant, BigDecimal> prices = pastPrices(28, Set.of(), Set.of(3, 9, 15, 21));

        assertThat(BacktestConfidence.of(dryer, usual, suggested, prices, now)).isEqualTo(new Confidence(24, 24));
    }

    @Test
    void withFewerThanFourteenDaysThereIsNoConfidence() {
        assertThat(BacktestConfidence.of(dryer, usual, suggested, pastPrices(13, Set.of(), Set.of()), now)).isNull();
        assertThat(BacktestConfidence.of(dryer, usual, suggested, pastPrices(14, Set.of(), Set.of()), now))
                .isEqualTo(new Confidence(14, 14));
    }

    @Test
    void aPastRunThatHadNotFinishedByNowIsNotCounted() {
        // Judged at 29 Sep 20:00: day 1's 19:45 run (29 Sep 19:45-20:45) hadn't finished.
        Map<Instant, BigDecimal> prices = pastPrices(28, Set.of(), Set.of());

        Confidence confidence = BacktestConfidence.of(dryer, usual, suggested, prices, sg("2026-09-29T20:00"));

        assertThat(confidence.daysChecked()).isEqualTo(27);
    }

    @Test
    void aSuggestionAfterMidnightIsComparedOnTheSameGap() {
        // EV usually 22:00 for 3 hours; suggested 02:00 the next day. Night prices 100, evening 300.
        ShiftableRun ev = new ShiftableRun(new BigDecimal("7.2"), 180, LocalTime.of(22, 0), LocalTime.of(7, 0),
                LocalTime.of(22, 0));
        Map<Instant, BigDecimal> prices = new HashMap<>();
        for (Instant t = sg("2026-08-30T00:00"); t.isBefore(now); t = t.plusSeconds(1800)) {
            int hour = t.atZone(SINGAPORE).getHour();
            prices.put(t, BigDecimal.valueOf(hour < 6 ? 100 : 300));
        }

        Confidence confidence = BacktestConfidence.of(ev, sg("2026-09-30T22:00"), sg("2026-10-01T02:00"),
                prices, now);

        // Day 1 compares 29 Sep 22:00 with 30 Sep 02:00 (finished by 08:10), and so on back 28 days.
        assertThat(confidence).isEqualTo(new Confidence(28, 28));
    }

    /**
     * Actual prices for the given number of past days: 300 at every half-hour, except 13:00-14:00
     * at 100 (so 13:00 is cheaper) unless the day is in notCheaper; days in missing lose 13:00.
     */
    private Map<Instant, BigDecimal> pastPrices(int days, Set<Integer> notCheaper, Set<Integer> missing) {
        Map<Instant, BigDecimal> prices = new HashMap<>();
        for (int day = 1; day <= days; day++) {
            Instant dayStart = sg("2026-09-30T00:00").minus(Duration.ofDays(day));
            for (int half = 0; half < 48; half++) {
                Instant period = dayStart.plusSeconds(1800L * half);
                int hour = period.atZone(SINGAPORE).getHour();
                boolean cheapSlot = hour == 13;
                if (cheapSlot && missing.contains(day)) {
                    continue;
                }
                prices.put(period, BigDecimal.valueOf(cheapSlot && !notCheaper.contains(day) ? 100 : 300));
            }
        }
        return prices;
    }

    private static Instant sg(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SINGAPORE).toInstant();
    }
}
