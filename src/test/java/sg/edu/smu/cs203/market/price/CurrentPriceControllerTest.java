package sg.edu.smu.cs203.market.price;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CurrentPriceControllerTest {
    private final MarketPriceService service = mock(MarketPriceService.class);

    @Test
    void dashboardContractConvertsUnitsAndExposesStalenessAndSourceTime() throws Exception {
        when(service.latest()).thenReturn(new LatestPriceResponse(new BigDecimal("280.03"),
                "SGD_PER_MWH", new BigDecimal("7560"), Instant.parse("2026-09-23T06:00:00Z"),
                Instant.parse("2026-09-23T06:01:00Z"), Instant.parse("2026-09-23T06:02:00Z"),
                "NEMS_SN_SG", PriceFreshness.STALE));
        MockMvcBuilders.standaloneSetup(new CurrentPriceController(service)).build()
                .perform(get("/api/v1/prices/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.centsPerKwh").value(28.0))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.marketPrice.sourceUpdatedAt").value("2026-09-23T06:01:00Z"));
    }

    @Test
    void missingPriceRetainsExplicitUnavailableResponse() throws Exception {
        when(service.latest()).thenThrow(new NoMarketPriceException());
        MockMvcBuilders.standaloneSetup(new CurrentPriceController(service))
                .setControllerAdvice(new MarketApiExceptionHandler()).build()
                .perform(get("/api/v1/prices/current"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.freshness").value("UNAVAILABLE"));
    }
}
