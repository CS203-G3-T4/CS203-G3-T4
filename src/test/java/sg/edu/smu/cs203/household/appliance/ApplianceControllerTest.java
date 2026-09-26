package sg.edu.smu.cs203.household.appliance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sg.edu.smu.cs203.household.HouseholdApiExceptionHandler;
import sg.edu.smu.cs203.household.HouseholdNotFoundException;
import sg.edu.smu.cs203.household.ValidationFailedException;

class ApplianceControllerTest {

    private static final String VALID_DRYER = """
            {"name": "Tumble Dryer", "type": "TUMBLE_DRYER", "powerKw": 2.5, "runMinutes": 60,
             "flexibility": "FLEXIBLE", "earliestStart": "07:00", "mustFinishBy": "21:00",
             "usualStart": "19:45", "runsPerWeek": 3, "enabled": true}
            """;

    private final ApplianceService service = mock(ApplianceService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplianceController(service))
            .setControllerAdvice(new HouseholdApiExceptionHandler())
            .build();

    @Test
    void addingAValidApplianceReturns201WithItsLocation() throws Exception {
        when(service.create(eq(1L), any())).thenReturn(response(10L));

        mvc.perform(post("/api/v1/households/1/appliances")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_DRYER))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/households/1/appliances/10"))
                .andExpect(jsonPath("$.energyPerRunKwh").value(2.5));
    }

    @Test
    void negativePowerIsA400WithAMessageForThatField() throws Exception {
        String body = VALID_DRYER.replace("\"powerKw\": 2.5", "\"powerKw\": -1");

        mvc.perform(post("/api/v1/households/1/appliances")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.powerKw").value("Power must be above 0 kW"));
        verify(service, never()).create(anyLong(), any());
    }

    @Test
    void crossFieldRuleFailuresAreA400WithFieldMessages() throws Exception {
        when(service.create(eq(1L), any())).thenThrow(new ValidationFailedException(
                Map.of("mustFinishBy", "This window is only 60 minutes long")));

        mvc.perform(post("/api/v1/households/1/appliances")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_DRYER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.mustFinishBy").value("This window is only 60 minutes long"));
    }

    @Test
    void unknownEnumValueIsA400NotA500() throws Exception {
        String body = VALID_DRYER.replace("\"FLEXIBLE\"", "\"SOMETIMES\"");

        mvc.perform(post("/api/v1/households/1/appliances")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownHouseholdIsA404() throws Exception {
        when(service.list(99L)).thenThrow(new HouseholdNotFoundException(99L));

        mvc.perform(get("/api/v1/households/99/appliances"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingReturns204AndMissingApplianceIsA404() throws Exception {
        mvc.perform(delete("/api/v1/households/1/appliances/10"))
                .andExpect(status().isNoContent());

        doThrow(new ApplianceNotFoundException(1L, 404L)).when(service).delete(1L, 404L);
        mvc.perform(delete("/api/v1/households/1/appliances/404"))
                .andExpect(status().isNotFound());
    }

    private ApplianceResponse response(long id) {
        return new ApplianceResponse(id, 1L, "Tumble Dryer", ApplianceType.TUMBLE_DRYER,
                new BigDecimal("2.500"), 60, new BigDecimal("2.500"), Flexibility.FLEXIBLE,
                LocalTime.of(7, 0), LocalTime.of(21, 0), LocalTime.of(19, 45), 840, false, 3,
                true, null);
    }
}
