package sg.edu.smu.cs203.recommendation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sg.edu.smu.cs203.household.HouseholdNotFoundException;

class RecommendationControllerTest {

    private final RecommendationService service = mock(RecommendationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RecommendationController(service))
            .setControllerAdvice(new RecommendationApiExceptionHandler())
            .build();

    @Test
    void acceptWithoutABodyUsesTheSuggestedTime() throws Exception {
        when(service.accept(eq(5L), isNull())).thenReturn(response(RecommendationStatus.ACCEPTED));

        mvc.perform(post("/api/v1/recommendations/5/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void acceptWithAnOverrideTimePassesItOn() throws Exception {
        when(service.accept(eq(5L), any())).thenReturn(response(RecommendationStatus.ACCEPTED));

        mvc.perform(post("/api/v1/recommendations/5/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"startTime\": \"15:10\"}"))
                .andExpect(status().isOk());
        verify(service).accept(5L, new AcceptRequest("15:10"));
    }

    @Test
    void aBadlyFormattedOverrideIsA400() throws Exception {
        mvc.perform(post("/api/v1/recommendations/5/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"startTime\": \"3pm\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.startTime").value("Use a time like 15:00"));
        verify(service, never()).accept(eq(5L), any());
    }

    @Test
    void dismissingADecidedSuggestionIsA409() throws Exception {
        when(service.dismiss(5L)).thenThrow(new RecommendationNotActiveException("This suggestion is already accepted"));

        mvc.perform(post("/api/v1/recommendations/5/dismiss"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This suggestion is already accepted"));
    }

    @Test
    void unknownSuggestionIsA404() throws Exception {
        when(service.dismiss(99L)).thenThrow(new RecommendationNotFoundException(99L));

        mvc.perform(post("/api/v1/recommendations/99/dismiss"))
                .andExpect(status().isNotFound());
    }

    @Test
    void generatingForAnUnknownHouseholdIsA404() throws Exception {
        when(service.generate(42L)).thenThrow(new HouseholdNotFoundException(42L));

        mvc.perform(post("/api/v1/households/42/recommendations/generate"))
                .andExpect(status().isNotFound());
    }

    private static RecommendationResponse response(RecommendationStatus status) {
        Instant at = Instant.parse("2026-09-30T05:00:00Z");
        return new RecommendationResponse(5L, 1L, 10L, "Tumble Dryer", "SHIFT_START", at, at, at,
                BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("9"), "reason", PriceForecast.DEMO, "demo", true,
                status, at, at);
    }
}
