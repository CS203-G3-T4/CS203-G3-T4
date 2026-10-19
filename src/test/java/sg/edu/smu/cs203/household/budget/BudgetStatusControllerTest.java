package sg.edu.smu.cs203.household.budget;

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

class BudgetStatusControllerTest {

    private final BudgetStatusService service = mock(BudgetStatusService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BudgetStatusController(service))
            .setControllerAdvice(new HouseholdApiExceptionHandler())
            .build();

    @Test
    void returnsTheSharedProjectionAndWhetherToAlert() throws Exception {
        when(service.status(1L)).thenReturn(new BudgetStatusResponse(1L, YearMonth.of(2026, 10),
                Instant.parse("2026-10-10T04:00:00Z"), PlanType.PRICE_LINKED, new BigDecimal("150.00"), true,
                new BigDecimal("50.00"), new BigDecimal("5.00"), 7, new BigDecimal("21.5"), new BigDecimal("157.50"),
                new BigDecimal("-7.50"), true, true, null, List.of()));

        mvc.perform(get("/api/v1/households/1/budget-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectedMonthSgd").value(157.50))
                .andExpect(jsonPath("$.alert").value(true));
    }

    @Test
    void anUnknownHouseholdIsA404() throws Exception {
        when(service.status(42L)).thenThrow(new HouseholdNotFoundException(42L));

        mvc.perform(get("/api/v1/households/42/budget-status")).andExpect(status().isNotFound());
    }
}
