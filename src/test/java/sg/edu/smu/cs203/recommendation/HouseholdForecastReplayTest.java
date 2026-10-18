package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sg.edu.smu.cs203.forecast.BaselineForecastService;
import sg.edu.smu.cs203.household.*;
import sg.edu.smu.cs203.household.appliance.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HouseholdForecastReplayTest {
    final Instant origin=Instant.parse("2026-07-31T16:00:00Z");
    final HouseholdDirectory directory=mock(HouseholdDirectory.class);
    final HouseholdForecastReplay replay=new HouseholdForecastReplay(directory);
    HouseholdResponse household(boolean exposed) {
        return new HouseholdResponse(1L,"SYNTHETIC_TEST_ONLY",DwellingType.HDB_4_ROOM,4,
            exposed?PlanType.PRICE_LINKED:PlanType.FIXED,exposed?null:BigDecimal.valueOf(30),1L,exposed,"fixture",false,origin);
    }
    HouseholdForecastReplay.Request request() {
        var targets=BaselineForecastService.targets(origin);
        var ai=targets.stream().map(t->new PriceForecast.HalfHourPrice(t,BigDecimal.valueOf(t.equals(targets.getFirst())?50:1000))).toList();
        var baseline=targets.stream().map(t->new PriceForecast.HalfHourPrice(t,BigDecimal.valueOf(t.equals(targets.get(1))?10:1000))).toList();
        var actual=targets.stream().map(t->new PriceForecast.HalfHourPrice(t,BigDecimal.valueOf(t.equals(targets.get(1))?10:400))).toList();
        return new HouseholdForecastReplay.Request(1,origin,origin.minusSeconds(3600),origin.minusSeconds(3600),
            "SYNTHETIC_TEST_ONLY","B2","FINAL","a".repeat(64),ai,baseline,actual);
    }
    @Test void actualF4FeasibilityAndRealizedCostKeepAiLossesAndFixedRates() {
        when(directory.household(1)).thenReturn(household(true));
        when(directory.flexibleAppliances(1)).thenReturn(List.of(new ApplianceResponse(1,1,"Fixture washer",ApplianceType.WASHING_MACHINE,
            BigDecimal.ONE,30,new BigDecimal("0.5"),Flexibility.FLEXIBLE,LocalTime.of(0,30),LocalTime.NOON,LocalTime.of(1,0),690,false,7,true,null)));
        var result=replay.compare(request());
        @SuppressWarnings("unchecked") var cases=(List<Map<String,Object>>)result.get("cases");
        assertThat(cases).hasSize(1);
        assertThat((BigDecimal)cases.getFirst().get("aiSavingsSgd")).isNegative();
        assertThat((BigDecimal)cases.getFirst().get("baselineSavingsSgd")).isZero();
        assertThat(cases.getFirst().get("aiStart")).isEqualTo(BaselineForecastService.targets(origin).getFirst());
        when(directory.household(1)).thenReturn(household(false));
        assertThat(replay.compare(request()).get("cases")).isEqualTo(List.of());
        assertThat(replay.compare(request()).get("fixedRateNotShifted")).isEqualTo(true);
    }
    @Test void missingActualsAndIncompleteForecastsDoNotBecomeSavings() {
        when(directory.household(1)).thenReturn(household(true));
        when(directory.flexibleAppliances(1)).thenReturn(List.of(new ApplianceResponse(1,1,"Fixture washer",ApplianceType.WASHING_MACHINE,
            BigDecimal.ONE,30,new BigDecimal("0.5"),Flexibility.FLEXIBLE,LocalTime.of(0,30),LocalTime.NOON,LocalTime.of(1,0),690,false,7,true,null)));
        var r=request();
        var missing=new HouseholdForecastReplay.Request(r.householdId(),r.asOf(),r.modelUsableFrom(),r.baselineValidationEnd(),
            r.modelVersion(),r.baselineMethod(),r.truthPolicy(),r.datasetDigest(),r.ai(),r.baseline(),List.of());
        assertThat(replay.compare(missing).get("cases")).isEqualTo(List.of());
        var incomplete=new HouseholdForecastReplay.Request(r.householdId(),r.asOf(),r.modelUsableFrom(),r.baselineValidationEnd(),
            r.modelVersion(),r.baselineMethod(),r.truthPolicy(),r.datasetDigest(),r.ai().subList(0,23),r.baseline(),r.actual());
        assertThatThrownBy(()->replay.compare(incomplete)).isInstanceOf(IllegalArgumentException.class);
    }
}
