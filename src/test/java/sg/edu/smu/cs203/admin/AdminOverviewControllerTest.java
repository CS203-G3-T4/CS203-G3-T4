package sg.edu.smu.cs203.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminOverviewControllerTest {

    private final AdminOverviewService service = mock(AdminOverviewService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminOverviewController(service)).build();

    @Test
    void returnsPlatformOverview() throws Exception {
        AdminOverviewResponse.HouseholdSummary household = new AdminOverviewResponse.HouseholdSummary(
                7L, "Simulated Home", "Price-linked (USEP)", new BigDecimal("4.25"),
                "ON_TRACK", true);
        when(service.overview()).thenReturn(new AdminOverviewResponse(
                Instant.parse("2026-10-23T14:00:00Z"), 3, 2, new BigDecimal("8.50"),
                new BigDecimal("12.34"), true, List.of(household)));

        mvc.perform(get("/api/v1/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.householdsMonitored").value(3))
                .andExpect(jsonPath("$.priceLinkedHouseholds").value(2))
                .andExpect(jsonPath("$.totalSavingsThisMonthSgd").value(8.50))
                .andExpect(jsonPath("$.households[0].simulated").value(true));
    }

    @Test
    void returnsHouseholdSummaryRows() throws Exception {
        AdminOverviewResponse.HouseholdSummary household = new AdminOverviewResponse.HouseholdSummary(
                8L, "Fixed Home", "Not exposed (fixed rate)", BigDecimal.ZERO,
                "NOT_EXPOSED", false);
        when(service.overview()).thenReturn(new AdminOverviewResponse(
                Instant.parse("2026-10-23T14:00:00Z"), 1, 0, BigDecimal.ZERO,
                null, false, List.of(household)));

        mvc.perform(get("/api/v1/admin/households/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Fixed Home"))
                .andExpect(jsonPath("$[0].status").value("NOT_EXPOSED"));
    }
}
