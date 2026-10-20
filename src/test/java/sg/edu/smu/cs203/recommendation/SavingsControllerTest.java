package sg.edu.smu.cs203.recommendation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sg.edu.smu.cs203.household.HouseholdAccessDeniedException;

/** CSDT4-53: GET /api/v1/households/{id}/savings?period=week|month. */
class SavingsControllerTest {

    private final RealisedSavingsService service = mock(RealisedSavingsService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SavingsController(service))
            .setControllerAdvice(new RecommendationApiExceptionHandler())
            .build();

    @Test
    void returnsTheTotalAndTheListBehindIt() throws Exception {
        when(service.savings(1L, SavingsPeriod.MONTH)).thenReturn(response(SavingsPeriod.MONTH));

        mvc.perform(get("/api/v1/households/1/savings").param("period", "month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("MONTH"))
                .andExpect(jsonPath("$.totalSavedSgd").value(0.41))
                .andExpect(jsonPath("$.items[0].state").value("REALISED"))
                .andExpect(jsonPath("$.items[0].savedSgd").value(0.41));
    }

    @Test
    void theWeekIsTheDefault() throws Exception {
        when(service.savings(1L, SavingsPeriod.WEEK)).thenReturn(response(SavingsPeriod.WEEK));

        mvc.perform(get("/api/v1/households/1/savings")).andExpect(status().isOk());
        verify(service).savings(1L, SavingsPeriod.WEEK);
    }

    @Test
    void anUnknownPeriodIsA400() throws Exception {
        mvc.perform(get("/api/v1/households/1/savings").param("period", "year"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.period").value("Use week or month"));
        verify(service, never()).savings(anyLong(), any());
    }

    @Test
    void anotherResidentsHouseholdIsA403() throws Exception {
        when(service.savings(2L, SavingsPeriod.WEEK)).thenThrow(new HouseholdAccessDeniedException());

        mvc.perform(get("/api/v1/households/2/savings")).andExpect(status().isForbidden());
    }

    private static SavingsResponse response(SavingsPeriod period) {
        Instant at = Instant.parse("2026-10-15T02:00:00Z");
        return new SavingsResponse(1L, period, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), at,
                new BigDecimal("0.41"), 1, 0, List.of(new SavingsResponse.Item(5L, 10L, "Tumble Dryer", at, at,
                        new BigDecimal("2.0"), 60, RealisedSavingsCalculator.State.REALISED, new BigDecimal("0.76"),
                        new BigDecimal("0.35"), new BigDecimal("0.41"), new BigDecimal("0.30"), null)),
                List.of(RealisedSavingsService.FOLLOWED_NOTE));
    }
}
