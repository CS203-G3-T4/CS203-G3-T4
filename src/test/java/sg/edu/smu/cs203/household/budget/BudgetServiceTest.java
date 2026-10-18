package sg.edu.smu.cs203.household.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.load.LoadProfileResponse;
import sg.edu.smu.cs203.household.load.LoadProfileService;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {
    @Mock HouseholdService households;
    @Mock LoadProfileService loads;
    @Mock BudgetRepository budgets;

    @Test
    void fixedPlanUsesSavedTariffAndProjectsWholeSingaporeMonth() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T05:00:00Z"), ZoneId.of("Asia/Singapore"));
        BudgetService service = new BudgetService(households, loads, budgets, clock);
        when(households.get(1)).thenReturn(household(PlanType.FIXED, "30.00"));
        when(budgets.find(1)).thenReturn(Optional.of(new BudgetRepository.Settings(
                new BigDecimal("100.00"), new BigDecimal("99.00"))));
        when(loads.profile(any(Long.class), any())).thenAnswer(call -> {
            java.time.LocalDate date = call.getArgument(1);
            return new LoadProfileResponse(1, date, "Test", BigDecimal.ZERO, true,
                    new BigDecimal("10.0000"), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, List.of(), List.of());
        });

        BudgetResponse result = service.get(1);

        assertEquals("FIXED_PLAN", result.rateSource());
        assertEquals(new BigDecimal("30.00"), result.rateCentsPerKwh());
        assertEquals(new BigDecimal("45.00"), result.estimatedSpendToDateSgd());
        assertEquals(new BigDecimal("90.00"), result.projectedSpendMonthSgd());
        assertEquals(new BigDecimal("10.00"), result.remainingBudgetSgd());
        assertEquals(30, result.days().size());
    }

    @Test
    void priceLinkedPlanWithoutRateShowsEnergyButNoSpend() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T05:00:00Z"), ZoneId.of("Asia/Singapore"));
        BudgetService service = new BudgetService(households, loads, budgets, clock);
        when(households.get(1)).thenReturn(household(PlanType.PRICE_LINKED, null));
        when(budgets.find(1)).thenReturn(Optional.empty());
        when(loads.profile(any(Long.class), any())).thenAnswer(call -> {
            java.time.LocalDate date = call.getArgument(1);
            return new LoadProfileResponse(1, date, "Test", BigDecimal.ZERO, true,
                    new BigDecimal("10.0000"), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, List.of(), List.of());
        });

        BudgetResponse result = service.get(1);

        assertEquals("NOT_SET", result.rateSource());
        assertEquals(new BigDecimal("150.0000"), result.modelledKwhToDate());
        assertNull(result.estimatedSpendToDateSgd());
        assertNull(result.projectedSpendMonthSgd());
        assertNull(result.remainingBudgetSgd());
    }

    @Test
    void priceLinkedPlanUsesResidentPlanningRate() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T05:00:00Z"), ZoneId.of("Asia/Singapore"));
        BudgetService service = new BudgetService(households, loads, budgets, clock);
        when(households.get(1)).thenReturn(household(PlanType.PRICE_LINKED, null));
        when(budgets.find(1)).thenReturn(Optional.of(new BudgetRepository.Settings(
                new BigDecimal("70.00"), new BigDecimal("25.00"))));
        when(loads.profile(any(Long.class), any())).thenAnswer(call -> {
            java.time.LocalDate date = call.getArgument(1);
            return new LoadProfileResponse(1, date, "Test", BigDecimal.ZERO, true,
                    new BigDecimal("10.0000"), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, List.of(), List.of());
        });

        BudgetResponse result = service.get(1);

        assertEquals("USER_PLANNING_RATE", result.rateSource());
        assertEquals(new BigDecimal("37.50"), result.estimatedSpendToDateSgd());
        assertEquals(new BigDecimal("75.00"), result.projectedSpendMonthSgd());
        assertEquals(new BigDecimal("-5.00"), result.remainingBudgetSgd());
    }

    private static HouseholdResponse household(PlanType plan, String fixedRate) {
        return new HouseholdResponse(1L, "Test", DwellingType.HDB_4_ROOM, 3, plan,
                fixedRate == null ? null : new BigDecimal(fixedRate), null,
                plan == PlanType.PRICE_LINKED, "Test", false, Instant.EPOCH);
    }
}
