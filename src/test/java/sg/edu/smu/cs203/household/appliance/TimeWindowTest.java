package sg.edu.smu.cs203.household.appliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class TimeWindowTest {

    @Test
    void daytimeWindowHasItsPlainLength() {
        TimeWindow window = TimeWindow.of(LocalTime.of(7, 0), LocalTime.of(21, 0));

        assertThat(window.lengthMinutes()).isEqualTo(14 * 60);
        assertThat(window.crossesMidnight()).isFalse();
    }

    @Test
    void overnightEvWindowCrossesMidnightAndFitsAThreeHourCharge() {
        TimeWindow window = TimeWindow.of(LocalTime.of(22, 0), LocalTime.of(7, 0));

        assertThat(window.lengthMinutes()).isEqualTo(9 * 60);
        assertThat(window.crossesMidnight()).isTrue();
        assertThat(window.fitsRunOf(180)).isTrue();
        assertThat(window.allowsStartAt(LocalTime.of(23, 30), 180)).isTrue();
        assertThat(window.allowsStartAt(LocalTime.of(4, 30), 180)).isFalse();
        assertThat(window.latestStartFor(180)).isEqualTo(LocalTime.of(4, 0));
    }

    @Test
    void sameStartAndEndMeansAnyTimeOfDay() {
        TimeWindow window = TimeWindow.of(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);

        assertThat(window.isAllDay()).isTrue();
        assertThat(window.lengthMinutes()).isEqualTo(1440);
        assertThat(window.allowsStartAt(LocalTime.of(23, 45), 30)).isTrue();
    }

    @Test
    void endingAtMidnightDoesNotCountAsCrossingIt() {
        TimeWindow window = TimeWindow.of(LocalTime.of(7, 0), LocalTime.MIDNIGHT);

        assertThat(window.crossesMidnight()).isFalse();
        assertThat(window.lengthMinutes()).isEqualTo(17 * 60);
    }

    @Test
    void dryerThatMustFinishBy9pmCannotStartAt930pm() {
        // The mockup's hero recommendation broke this rule (CSDT4-19 builds on it).
        TimeWindow window = TimeWindow.of(LocalTime.of(7, 0), LocalTime.of(21, 0));

        assertThat(window.allowsStartAt(LocalTime.of(21, 30), 60)).isFalse();
        assertThat(window.allowsStartAt(LocalTime.of(20, 0), 60)).isTrue();
        assertThat(window.latestStartFor(60)).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void windowShorterThanTheRunDoesNotFit() {
        TimeWindow window = TimeWindow.of(LocalTime.of(8, 0), LocalTime.of(9, 0));

        assertThat(window.fitsRunOf(90)).isFalse();
        assertThat(window.latestStartFor(90)).isNull();
    }
}
