package sg.edu.smu.cs203.household.appliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ApplianceRulesTest {

    @Test
    void validFlexibleDryerHasNoErrors() {
        ApplianceRequest dryer = request(Flexibility.FLEXIBLE, 60,
                LocalTime.of(7, 0), LocalTime.of(21, 0), LocalTime.of(19, 45));

        assertThat(ApplianceRules.check(dryer)).isEmpty();
    }

    @Test
    void flexibleApplianceNeedsBothWindowTimes() {
        Map<String, String> errors = ApplianceRules.check(
                request(Flexibility.FLEXIBLE, 60, null, null, null));

        assertThat(errors).containsKeys("earliestStart", "mustFinishBy");
    }

    @Test
    void windowShorterThanRunIsRejectedOnMustFinishBy() {
        Map<String, String> errors = ApplianceRules.check(
                request(Flexibility.FLEXIBLE, 90, LocalTime.of(8, 0), LocalTime.of(9, 0), null));

        assertThat(errors).containsOnlyKeys("mustFinishBy");
        assertThat(errors.get("mustFinishBy")).contains("60 minutes").contains("90-minute run");
    }

    @Test
    void usualStartThatWouldOverrunTheWindowIsRejected() {
        Map<String, String> errors = ApplianceRules.check(request(Flexibility.FLEXIBLE, 60,
                LocalTime.of(7, 0), LocalTime.of(21, 0), LocalTime.of(21, 30)));

        assertThat(errors).containsOnlyKeys("usualStart");
        assertThat(errors.get("usualStart")).contains("20:00");
    }

    @Test
    void overnightEvWindowIsAccepted() {
        ApplianceRequest ev = request(Flexibility.FLEXIBLE, 180,
                LocalTime.of(22, 0), LocalTime.of(7, 0), LocalTime.of(23, 0));

        assertThat(ApplianceRules.check(ev)).isEmpty();
    }

    @Test
    void fixedApplianceNeedsAStartTimeAndNoWindow() {
        Map<String, String> errors = ApplianceRules.check(request(Flexibility.FIXED, 240,
                LocalTime.of(18, 0), LocalTime.of(22, 0), null));

        assertThat(errors).containsKeys("usualStart", "earliestStart", "mustFinishBy");
    }

    @Test
    void notFlexibleApplianceNeedsNoTimes() {
        assertThat(ApplianceRules.check(
                request(Flexibility.NOT_FLEXIBLE, 1440, null, null, null))).isEmpty();
    }

    static ApplianceRequest request(Flexibility flexibility, int runMinutes, LocalTime earliest,
                                    LocalTime finishBy, LocalTime usual) {
        return new ApplianceRequest("Tumble Dryer", ApplianceType.TUMBLE_DRYER,
                new BigDecimal("2.5"), runMinutes, flexibility, earliest, finishBy, usual,
                3, true, null);
    }
}
