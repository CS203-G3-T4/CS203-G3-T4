package sg.edu.smu.cs203.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sg.edu.smu.cs203.forecast.ForecastAccuracyService;
import sg.edu.smu.cs203.household.DwellingType;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.budget.BudgetStatusResponse;
import sg.edu.smu.cs203.household.budget.BudgetStatusService;
import sg.edu.smu.cs203.recommendation.RealisedSavingsService;
import sg.edu.smu.cs203.recommendation.SavingsPeriod;
import sg.edu.smu.cs203.recommendation.SavingsResponse;

class AdminOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T14:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final HouseholdService households = mock(HouseholdService.class);
    private final BudgetStatusService budgets = mock(BudgetStatusService.class);
    private final RealisedSavingsService savings = mock(RealisedSavingsService.class);
    private final ForecastAccuracyService accuracy = mock(ForecastAccuracyService.class);

    @Test
    void calculatesTotalsAndEachHouseholdStatus() {
        HouseholdResponse onTrack = household(1L, "On track", PlanType.PRICE_LINKED, false);
        HouseholdResponse overPace = household(2L, "Over pace", PlanType.PRICE_LINKED, false);
        HouseholdResponse noBudget = household(3L, "No budget", PlanType.PRICE_LINKED, true);
        HouseholdResponse fixed = household(4L, "Fixed", PlanType.FIXED, false);
        List<HouseholdResponse> all = List.of(onTrack, overPace, noBudget, fixed);
        when(households.list(true)).thenReturn(all);

        when(savings.savings(1L, SavingsPeriod.MONTH)).thenReturn(savings(1L, "1.25"));
        when(savings.savings(2L, SavingsPeriod.MONTH)).thenReturn(savings(2L, "2.50"));
        when(savings.savings(3L, SavingsPeriod.MONTH)).thenReturn(savings(3L, "0.75"));
        when(savings.savings(4L, SavingsPeriod.MONTH)).thenReturn(savings(4L, "1.00"));

        when(budgets.status(1L)).thenReturn(budget(1L, "100.00", false));
        when(budgets.status(2L)).thenReturn(budget(2L, "100.00", true));
        when(budgets.status(3L)).thenReturn(budget(3L, null, false));
        when(budgets.status(4L)).thenReturn(budget(4L, "100.00", true));

        Map<String, Object> score = new HashMap<>();
        score.put("model", "AI");
        score.put("mae", "3.21");
        when(accuracy.read(7, 1, "PROVISIONAL_FIRST_EVALUATION", NOW, "LIVE"))
                .thenReturn(Map.of("commonScores", List.of(score)));

        AdminOverviewResponse result = new AdminOverviewService(
                households, budgets, savings, accuracy, FIXED_CLOCK, "LIVE").overview();

        assertEquals(4, result.householdsMonitored());
        assertEquals(3, result.priceLinkedHouseholds());
        assertEquals(new BigDecimal("5.50"), result.totalSavingsThisMonthSgd());
        assertEquals(new BigDecimal("3.21"), result.forecastMaeSgdPerMwh());
        assertEquals(List.of("ON_TRACK", "OVER_PACE", "NO_BUDGET", "NOT_EXPOSED"),
                result.households().stream().map(AdminOverviewResponse.HouseholdSummary::status).toList());
    }

    private static HouseholdResponse household(long id, String name, PlanType plan, boolean simulated) {
        return new HouseholdResponse(id, name, DwellingType.HDB_4_ROOM, 4, plan, null, null,
                plan == PlanType.PRICE_LINKED, plan == PlanType.PRICE_LINKED
                        ? "Price-linked (USEP)" : "Not exposed (fixed rate)", simulated, NOW);
    }

    private static SavingsResponse savings(long householdId, String total) {
        return new SavingsResponse(householdId, SavingsPeriod.MONTH, null, null, NOW,
                new BigDecimal(total), 1, 0, List.of(), List.of());
    }

    private static BudgetStatusResponse budget(long householdId, String monthlyBudget, boolean overBudget) {
        return new BudgetStatusResponse(householdId, null, NOW, PlanType.PRICE_LINKED,
                monthlyBudget == null ? null : new BigDecimal(monthlyBudget), true, BigDecimal.ZERO,
                null, 0, BigDecimal.ZERO, null, null, overBudget, false, null, List.of());
    }
}
