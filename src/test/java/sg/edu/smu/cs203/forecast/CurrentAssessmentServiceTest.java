package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sg.edu.smu.cs203.common.DemoClock;
import sg.edu.smu.cs203.market.price.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentAssessmentServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-02T12:02:00Z");
    private static final Instant SLOT=Instant.parse("2026-11-02T12:00:00Z");
    private final MarketHistoryRepository history=mock(MarketHistoryRepository.class);
    private final ForecastService forecasts=mock(ForecastService.class);
    private final CurrentAssessmentService service=new CurrentAssessmentService(history,
            Clock.fixed(NOW,ZoneId.of("Asia/Singapore")),forecasts,14,BigDecimal.valueOf(3),BigDecimal.ONE,BigDecimal.ONE);

    private List<MarketRevision> rows(int days,int current) {
        var rows=new ArrayList<MarketRevision>();
        for(int d=days;d>=0;d--) {
            Instant p=SLOT.minus(Duration.ofDays(d));
            rows.add(new MarketRevision(d,"SYNTHETIC",p,p.plusSeconds(60),p.plusSeconds(90),
                    BigDecimal.valueOf(d==0?current:100),null,null,"PROVISIONAL","EMC_PERIOD"));
        }
        when(history.asOf(NOW)).thenReturn(rows);
        when(forecasts.mappingVerified(rows)).thenReturn(true);
        return rows;
    }

    @ParameterizedTest
    @CsvSource({"80,CHEAP","100,NORMAL","103,EXPENSIVE","120,SPIKE"})
    void reliablePricesKeepTheFixedRuleAndExposeTheirPeriod(int price,String label) {
        rows(28,price);
        var result=service.current();
        assertThat(result.available()).isTrue();
        assertThat(result.classification()).isEqualTo(label);
        assertThat(result.periodStart()).isEqualTo(SLOT);
        assertThat(result.typical()).isEqualByComparingTo("100");
        assertThat(result.spikeThreshold()).isEqualByComparingTo("103");
        assertThat(result.reason()).isNull();
    }

    @Test
    void stalePriceRemainsVisibleButHasNoConfidentAssessment() {
        var rows=rows(28,120);when(forecasts.stale(rows,NOW)).thenReturn(true);
        assertUnavailable(service.current(),"STALE_PRICE",true);
    }

    @Test
    void unverifiedIntervalsHaveNoConfidentAssessmentEvenWithEnoughHistory() {
        var rows=rows(28,120);when(forecasts.mappingVerified(rows)).thenReturn(false);
        assertUnavailable(service.current(),"PERIOD_MAPPING_UNVERIFIED",false);
    }

    @Test
    void insufficientHistoryRetainsItsReasonAndActualSampleCount() {
        rows(13,120);var result=service.current();
        assertUnavailable(result,"INSUFFICIENT_REFERENCE_HISTORY",false);
        assertThat(result.sampleCount()).isEqualTo(13);
    }

    @Test
    void futureReceiptsDoNotCompleteTheMinimumReference() {
        var rows=rows(14,120);var old=rows.getFirst();
        rows.set(0,new MarketRevision(old.id(),old.source(),old.periodStart(),old.sourceUpdatedAt(),
                NOW.plusSeconds(1),old.usep(),null,null,old.priceStatus(),old.timeMapping()));
        var result=service.current();
        assertUnavailable(result,"INSUFFICIENT_REFERENCE_HISTORY",false);
        assertThat(result.sampleCount()).isEqualTo(13);
    }

    @Test
    void absentPriceCannotInventAPeriodOrClassification() {
        when(history.asOf(NOW)).thenReturn(List.of());var result=service.current();
        assertThat(result.available()).isFalse();assertThat(result.actualPrice()).isNull();
        assertThat(result.periodStart()).isNull();assertThat(result.classification()).isEqualTo("UNAVAILABLE");
        assertThat(result.reason()).isEqualTo("NO_PRICE");
    }

    @Test
    void demoUsesTheReplayPricePeriodAndStillRequiresVerifiedReferenceInputs() {
        var rows=rows(28,120);
        DemoClock clock=new DemoClock(Clock.fixed(NOW,ZoneId.of("Asia/Singapore")));clock.startAt(NOW);
        var replay=mock(ReplayPriceService.class);
        var price=mock(MarketPrice.class);
        when(price.intervalStart()).thenReturn(SLOT);
        when(price.usepSgdPerMwh()).thenReturn(BigDecimal.valueOf(120));
        when(replay.latest(NOW)).thenReturn(Optional.of(price));
        var demo=new CurrentAssessmentService(history,clock,forecasts,14,BigDecimal.valueOf(3),BigDecimal.ONE,BigDecimal.ONE);
        demo.setReplayPrices(replay);
        assertThat(demo.current().classification()).isEqualTo("SPIKE");
        assertThat(demo.current().periodStart()).isEqualTo(SLOT);
        when(forecasts.mappingVerified(rows)).thenReturn(false);
        assertUnavailable(demo.current(),"PERIOD_MAPPING_UNVERIFIED",false);
        clock.stop();
    }

    private static void assertUnavailable(ForecastTypes.Assessment result,String reason,boolean stale) {
        assertThat(result.available()).isFalse();assertThat(result.stale()).isEqualTo(stale);
        assertThat(result.classification()).isEqualTo("UNAVAILABLE");assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.actualPrice()).isEqualByComparingTo("120");assertThat(result.periodStart()).isEqualTo(SLOT);
        assertThat(result.typical()).isNull();assertThat(result.deviation()).isNull();
        assertThat(result.deviationPercent()).isNull();assertThat(result.spikeThreshold()).isNull();
    }
}
