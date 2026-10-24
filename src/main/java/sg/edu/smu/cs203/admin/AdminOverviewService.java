package sg.edu.smu.cs203.admin;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.forecast.ForecastAccuracyService;
import sg.edu.smu.cs203.household.HouseholdResponse;
import sg.edu.smu.cs203.household.HouseholdService;
import sg.edu.smu.cs203.household.PlanType;
import sg.edu.smu.cs203.household.budget.BudgetStatusResponse;
import sg.edu.smu.cs203.household.budget.BudgetStatusService;
import sg.edu.smu.cs203.recommendation.RealisedSavingsService;
import sg.edu.smu.cs203.recommendation.SavingsPeriod;
import sg.edu.smu.cs203.recommendation.SavingsResponse;

@Service
public class AdminOverviewService {

    static final String ON_TRACK = "ON_TRACK";
    static final String OVER_PACE = "OVER_PACE";
    static final String NO_BUDGET = "NO_BUDGET";
    static final String NOT_EXPOSED = "NOT_EXPOSED";

    private final HouseholdService households;
    private final BudgetStatusService budgets;
    private final RealisedSavingsService savings;
    private final ForecastAccuracyService accuracy;
    private final Clock clock;
    private final String forecastMode;

    public AdminOverviewService(HouseholdService households, BudgetStatusService budgets,
                                RealisedSavingsService savings, ForecastAccuracyService accuracy,
                                Clock clock, @Value("${forecast.mode:LIVE}") String forecastMode) {
        this.households = households;
        this.budgets = budgets;
        this.savings = savings;
        this.accuracy = accuracy;
        this.clock = clock;
        this.forecastMode = forecastMode;
    }

    public AdminOverviewResponse overview() {
        Instant now = clock.instant();
        List<HouseholdResponse> all = households.list(true);
        List<AdminOverviewResponse.HouseholdSummary> summaries = new ArrayList<>();
        BigDecimal totalSavings = BigDecimal.ZERO.setScale(2);
        int priceLinked = 0;
        for (HouseholdResponse household : all) {
            if (household.exposedToWholesalePrice()) {
                priceLinked++;
            }
            SavingsResponse savingsForMonth = savings.savings(household.id(), SavingsPeriod.MONTH);
            BigDecimal saved = savingsForMonth.totalSavedSgd();
            totalSavings = totalSavings.add(saved);
            summaries.add(new AdminOverviewResponse.HouseholdSummary(
                    household.id(), household.name(), household.exposureLabel(), saved,
                    status(household, budgets.status(household.id())), household.simulated()));
        }

        AccuracyMetric metric = accuracyMetric(now);
        return new AdminOverviewResponse(now, all.size(), priceLinked, totalSavings,
                metric.mae(), metric.available(), summaries);
    }

    private static String status(HouseholdResponse household, BudgetStatusResponse budget) {
        if (household.planType() == PlanType.FIXED) {
            return NOT_EXPOSED;
        }
        if (budget.monthlyBudgetSgd() == null) {
            return NO_BUDGET;
        }
        return budget.overBudget() ? OVER_PACE : ON_TRACK;
    }

    @SuppressWarnings("unchecked")
    private AccuracyMetric accuracyMetric(Instant now) {
        Map<String, Object> result = accuracy.read(7, 1, "PROVISIONAL_FIRST_EVALUATION", now, forecastMode);
        List<Map<String, Object>> scores = (List<Map<String, Object>>) result.get("commonScores");
        if (scores == null) {
            return new AccuracyMetric(null, false);
        }
        Map<String, Object> preferred = scores.stream()
                .filter(score -> "AI".equals(score.get("model")))
                .findFirst()
                .orElse(scores.isEmpty() ? null : scores.get(0));
        if (preferred == null || preferred.get("mae") == null) {
            return new AccuracyMetric(null, false);
        }
        return new AccuracyMetric(new BigDecimal(preferred.get("mae").toString()), true);
    }

    private record AccuracyMetric(BigDecimal mae, boolean available) {
    }
}
