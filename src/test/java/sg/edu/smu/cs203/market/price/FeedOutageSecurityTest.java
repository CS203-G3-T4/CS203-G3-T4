package sg.edu.smu.cs203.market.price;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;
import sg.edu.smu.cs203.security.SecurityConfiguration;

/** CSDT4-75: only admins can flip the outage switch, and changing it needs a CSRF token. */
@WebMvcTest(AdminFeedOutageController.class)
@ContextConfiguration(classes = EnergyMarketServiceApplication.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({SecurityConfiguration.class, AdminFeedOutageController.class, FeedOutageSwitch.class,
        FeedOutageSecurityTest.FixedTime.class})
class FeedOutageSecurityTest {

    private static final String ON = "{\"enabled\": true}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MarketIngestionService ingestion;

    @MockitoBean
    private MarketPriceService prices;

    @Test
    void anonymousUsersAndResidentsCannotReadOrChangeTheSwitch() throws Exception {
        mvc.perform(get("/api/v1/admin/feed-outage")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/feed-outage").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ON))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/feed-outage").with(user("resident").roles("RESIDENT")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(ON))
                .andExpect(status().isForbidden());
        verify(ingestion, never()).poll();
    }

    @Test
    void adminsNeedACsrfTokenToChangeTheSwitch() throws Exception {
        mvc.perform(put("/api/v1/admin/feed-outage").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(ON))
                .andExpect(status().isForbidden());
        verify(ingestion, never()).poll();

        mvc.perform(put("/api/v1/admin/feed-outage").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(ON))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/feed-outage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        verify(ingestion).poll();
    }

    @TestConfiguration
    static class FixedTime {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-19T08:05:00Z"), ZoneOffset.UTC);
        }
    }
}
