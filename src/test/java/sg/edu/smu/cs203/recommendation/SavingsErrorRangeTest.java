package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SavingsErrorRangeTest {
    final LocalDate today=LocalDate.of(2026,10,30);
    @Test void twentyIndependentDaysAreRequiredRatherThanManyCorrelatedRows() {
        var sameDay=Collections.nCopies(500,new SavingsErrorRange.Sample(today.minusDays(1),new BigDecimal(".04")));
        var range=SavingsErrorRange.calculate(new BigDecimal(".20"),BigDecimal.ONE,sameDay,today);
        assertThat(range.available()).isFalse();assertThat(range.completedDays()).isEqualTo(1);
    }
    @Test void futureAndCurrentDayOutcomesAreNotCalibrationAndNegativeSavingsRemainPossible() {
        List<SavingsErrorRange.Sample> samples=new ArrayList<>();
        for(int i=1;i<=20;i++) samples.add(new SavingsErrorRange.Sample(today.minusDays(i),new BigDecimal(".10")));
        samples.add(new SavingsErrorRange.Sample(today,new BigDecimal("999")));
        samples.add(new SavingsErrorRange.Sample(today.plusDays(1),new BigDecimal("999")));
        var range=SavingsErrorRange.calculate(new BigDecimal(".03"),new BigDecimal(".5"),samples,today);
        assertThat(range.available()).isTrue();assertThat(range.samples()).isEqualTo(20);
        assertThat(range.lowerSgd()).isEqualByComparingTo("-.02");assertThat(range.upperSgd()).isEqualByComparingTo(".08");
    }
    @Test void dailyMaximumIsUsedAndEnergyScalingMatchesAnApplianceRun() {
        List<SavingsErrorRange.Sample> samples=new ArrayList<>();
        for(int i=1;i<=20;i++) {samples.add(new SavingsErrorRange.Sample(today.minusDays(i),new BigDecimal(".02")));
            samples.add(new SavingsErrorRange.Sample(today.minusDays(i),new BigDecimal("-.06")));}
        var range=SavingsErrorRange.calculate(new BigDecimal(".20"),SavingsErrorRange.energy(new BigDecimal("2"),90),samples,today);
        assertThat(range.lowerSgd()).isEqualByComparingTo(".02");assertThat(range.upperSgd()).isEqualByComparingTo(".38");
    }
}
