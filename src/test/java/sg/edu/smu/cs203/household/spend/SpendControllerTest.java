package sg.edu.smu.cs203.household.spend;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sg.edu.smu.cs203.household.HouseholdApiExceptionHandler;
import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.PlanType;

class SpendControllerTest {

    private final SpendService service = mock(SpendService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpendController(service))
            .setControllerAdvice(new HouseholdApiExceptionHandler())
            .build();

    @Test
    void returnsTheSpendWithEachChargesSource() throws Exception {
        when(service.monthToDate(1L)).thenReturn(new MonthToDateSpendResponse(1L, YearMonth.of(2026, 10),
                Instant.parse("2026-10-18T07:00:00Z"), PlanType.PRICE_LINKED, null, new BigDecimal("38.21"),
                new BigDecimal("25.10"), new BigDecimal("3.15"), new BigDecimal("190.5"), BigDecimal.ZERO, 830, 830, 0,
                List.of(new MonthToDateSpendResponse.Charge("GST", "GST (9%)", "PERCENT", new BigDecimal("9"),
                        new BigDecimal("3.15"), "SP Group fact sheet", "https://www.spgroup.com.sg/x.pdf")),
                List.of()));

        mvc.perform(get("/api/v1/households/1/spend/month-to-date"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSgd").value(38.21))
                .andExpect(jsonPath("$.month").value("2026-10"))
                .andExpect(jsonPath("$.charges[0].source").value("SP Group fact sheet"));
    }

    @Test
    void anUnknownHouseholdIsA404() throws Exception {
        when(service.monthToDate(42L)).thenThrow(new HouseholdNotFoundException(42L));

        mvc.perform(get("/api/v1/households/42/spend/month-to-date")).andExpect(status().isNotFound());
    }

    @Test
    void returnsWeeklySpendAndBudgetPaceForTheRequestedMonth() throws Exception {
        when(service.weekly(1L, YearMonth.of(2026, 10))).thenReturn(new WeeklySpendResponse(1L,
                YearMonth.of(2026, 10), Instant.parse("2026-10-23T14:00:00Z"), new BigDecimal("120.00"),
                List.of(new WeeklySpendResponse.Week(YearMonth.of(2026, 10).atDay(1),
                        YearMonth.of(2026, 10).atDay(4), new BigDecimal("18.50"), new BigDecimal("15.48"),
                        false, 4))));

        mvc.perform(get("/api/v1/households/1/spend/weekly").param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyBudgetSgd").value(120.00))
                .andExpect(jsonPath("$.weeks[0].spentSgd").value(18.50))
                .andExpect(jsonPath("$.weeks[0].budgetPaceSgd").value(15.48));
    }
}
