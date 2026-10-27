package sg.edu.smu.cs203.common;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.mockito.Mockito;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;
import sg.edu.smu.cs203.security.CsrfTokenController;
import sg.edu.smu.cs203.security.SecurityConfiguration;
import sg.edu.smu.cs203.market.price.ReplayPriceService;

@WebMvcTest(DemoClockController.class)
@ContextConfiguration(classes = EnergyMarketServiceApplication.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({SecurityConfiguration.class, DemoClockController.class, CsrfTokenController.class,
        DemoClockControllerTest.ClockConfiguration.class})
class DemoClockControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private DemoClock demoClock;
    @Autowired
    private ReplayPriceService replayPrices;
    @Autowired
    private DemoReplayRefreshService replayRefresh;

    @BeforeEach
    void resetClockAndMocks() {
        demoClock.stop();
        Mockito.reset(replayPrices, replayRefresh);
    }

    @Test
    void startAdvanceAndStopUseTheDateTimeAndPeriodsContractWithFixedInstants() throws Exception {
        mvc.perform(get("/api/v1/demo/state").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.currentTime").value("2026-10-24T08:00:00Z"))
                .andExpect(jsonPath("$.zone").value("Asia/Singapore"));

        mvc.perform(post("/api/v1/demo/start")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-26\",\"time\":\"08:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.currentTime").value("2026-09-26T00:00:00Z"));

        mvc.perform(post("/api/v1/demo/advance")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periods\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentTime").value("2026-09-26T01:00:00Z"));

        mvc.perform(post("/api/v1/demo/stop")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.currentTime").value("2026-10-24T08:00:00Z"))
                .andExpect(jsonPath("$.zone").value("Asia/Singapore"));
    }

    @Test
    void rejectsAdvancingAStoppedClockAndInvalidHalfHourCounts() throws Exception {
        mvc.perform(post("/api/v1/demo/advance")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periods\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot advance when the demo clock is stopped"));

        mvc.perform(post("/api/v1/demo/advance")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periods\":0}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/demo/advance")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periods\":2147483647}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allDemoEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/api/v1/demo/state"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/demo/state").with(user("resident").roles("RESIDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/demo/start")
                        .with(user("resident").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-26\",\"time\":\"08:00\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/demo/advance")
                        .with(user("resident").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periods\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/demo/stop")
                        .with(user("resident").roles("RESIDENT")).with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/demo/start")
                        .with(user("resident").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-26\",\"time\":\"08:00\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void startRequiresBothDateAndTime() throws Exception {
        mvc.perform(post("/api/v1/demo/start")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-26\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startLoadsTheSelectedReplayDayAndEveryClockMoveRefreshesDemoData() throws Exception {
        mvc.perform(post("/api/v1/demo/start")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-26\",\"time\":\"08:00\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/demo/advance")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periods\":1}"))
                .andExpect(status().isOk());

        Mockito.verify(replayPrices).loadDay(LocalDate.parse("2026-09-26"));
        Mockito.verify(replayRefresh, Mockito.times(2)).refresh();
    }

    @Test
    void failedRefreshDoesNotLeaveTheClockAtAnUnrefreshedTime() throws Exception {
        Mockito.doThrow(new IllegalStateException("refresh failed"))
                .when(replayRefresh).refresh();
        mvc.perform(post("/api/v1/demo/start")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-26\",\"time\":\"08:00\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/demo/state").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.running").value(false));

        Mockito.reset(replayRefresh);
        mvc.perform(post("/api/v1/demo/start")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2026-09-26\",\"time\":\"08:00\"}"))
                .andExpect(status().isOk());
        Mockito.doThrow(new IllegalStateException("refresh failed"))
                .when(replayRefresh).refresh();
        mvc.perform(post("/api/v1/demo/advance")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periods\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/demo/state").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.currentTime").value("2026-09-26T00:00:00Z"));
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        DemoClock demoClock() {
            return new DemoClock(Clock.fixed(
                    Instant.parse("2026-10-24T08:00:00Z"), ZoneId.of("Asia/Singapore")));
        }

        @Bean
        ReplayPriceService replayPriceService() {
            return Mockito.mock(ReplayPriceService.class);
        }

        @Bean
        DemoReplayRefreshService demoReplayRefreshService() {
            return Mockito.mock(DemoReplayRefreshService.class);
        }
    }
}
