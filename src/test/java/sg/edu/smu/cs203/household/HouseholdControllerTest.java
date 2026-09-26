package sg.edu.smu.cs203.household;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Add and delete households through the API (CSDT4-62). */
class HouseholdControllerTest {

    private static final String VALID_HOUSEHOLD = """
            {"name": "The Wongs", "dwellingType": "PRIVATE_APARTMENT_CONDO", "occupants": 2,
             "planType": "PRICE_LINKED", "fixedRateCentsPerKwh": null}
            """;

    private final HouseholdService service = mock(HouseholdService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new HouseholdController(service))
            .setControllerAdvice(new HouseholdApiExceptionHandler())
            .build();

    @Test
    void addingAValidHouseholdReturns201WithItsLocation() throws Exception {
        when(service.create(any())).thenReturn(response(7L));

        mvc.perform(post("/api/v1/households")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_HOUSEHOLD))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/households/7"))
                .andExpect(jsonPath("$.name").value("The Wongs"));
    }

    @Test
    void blankNameIsA400WithAMessageForThatField() throws Exception {
        String body = VALID_HOUSEHOLD.replace("\"The Wongs\"", "\"  \"");

        mvc.perform(post("/api/v1/households")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Give the household a name"));
        verify(service, never()).create(any());
    }

    @Test
    void deletingAHouseholdReturns204() throws Exception {
        mvc.perform(delete("/api/v1/households/7"))
                .andExpect(status().isNoContent());
        verify(service).delete(7L);
    }

    @Test
    void deletingAnUnknownHouseholdIsA404() throws Exception {
        doThrow(new HouseholdNotFoundException(99L)).when(service).delete(99L);

        mvc.perform(delete("/api/v1/households/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Household 99 does not exist"));
    }

    @Test
    void deletingASimulatedHouseholdIsA409() throws Exception {
        doThrow(new HouseholdNotDeletableException(42L)).when(service).delete(42L);

        mvc.perform(delete("/api/v1/households/42"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Household cannot be deleted"));
    }

    private static HouseholdResponse response(long id) {
        Instant created = Instant.parse("2026-09-26T06:00:00Z");
        return HouseholdResponse.from(new Household(id, "The Wongs",
                DwellingType.PRIVATE_APARTMENT_CONDO, 2, PlanType.PRICE_LINKED, null, 5L, false,
                created, created));
    }
}
