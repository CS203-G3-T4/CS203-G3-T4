package sg.edu.smu.cs203.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;

@WebMvcTest(AdminApiAuthorizationTest.AdminApi.class)
@ContextConfiguration(classes = EnergyMarketServiceApplication.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({SecurityConfiguration.class, AdminApiAuthorizationTest.AdminApi.class,
        CsrfTokenController.class})
class AdminApiAuthorizationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousRequestsToAdminAndDemoApisReceiveForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/test")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/demo")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/demo/test")).andExpect(status().isForbidden());
    }

    @Test
    void forecastPageIsPublicButAdminForecastPageRequiresForecastAuthentication() throws Exception {
        mvc.perform(get("/forecast.html")).andExpect(status().isOk());
        mvc.perform(get("/admin-forecast.html")).andExpect(status().isUnauthorized());
    }

    @Test
    void residentRequestsToAdminAndDemoApisReceiveForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin").with(user("resident").roles("RESIDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/test").with(user("resident").roles("RESIDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/demo").with(user("resident").roles("RESIDENT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/demo/test").with(user("resident").roles("RESIDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminsCanAccessAdminAndDemoApis() throws Exception {
        mvc.perform(get("/api/v1/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/test").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/demo").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/demo/test").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void csrfTokenFromEndpointIsAcceptedInTheConfiguredHeader() throws Exception {
        MvcResult tokenResponse = mvc.perform(get("/api/v1/csrf")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        Matcher headerName = Pattern.compile("\"headerName\":\"([^\"]+)\"")
                .matcher(tokenResponse.getResponse().getContentAsString());
        Matcher token = Pattern.compile("\"token\":\"([^\"]+)\"")
                .matcher(tokenResponse.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(headerName.find());
        org.junit.jupiter.api.Assertions.assertTrue(token.find());

        mvc.perform(post("/api/v1/households")
                        .with(user("admin").roles("ADMIN"))
                        .session((MockHttpSession) tokenResponse.getRequest().getSession(false))
                        .cookie(tokenResponse.getResponse().getCookie("XSRF-TOKEN"))
                        .header(headerName.group(1), token.group(1)))
                .andExpect(status().isCreated());
    }

    @Test
    void householdCreationAndDeletionRequireAdminRoleAndCsrf() throws Exception {
        mvc.perform(post("/api/v1/households").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/households").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/households/1").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void residentsAndAnonymousUsersCannotCreateOrDeleteHouseholds() throws Exception {
        mvc.perform(post("/api/v1/households").with(user("resident").roles("RESIDENT")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/households/1").with(user("resident").roles("RESIDENT")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/households").with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/households/1").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void householdDeletionGuardDoesNotCatchApplianceDeletion() throws Exception {
        mvc.perform(delete("/api/v1/households/1/appliances/2")
                        .with(user("resident").roles("RESIDENT")).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @RestController
    static class AdminApi {

        @GetMapping({"/api/v1/admin", "/api/v1/admin/test", "/api/v1/demo", "/api/v1/demo/test"})
        String adminEndpoint() {
            return "admin";
        }

        @GetMapping("/api/v1/forecast/latest")
        String publicForecast() {
            return "forecast";
        }

        @PostMapping("/api/v1/households")
        @ResponseStatus(HttpStatus.CREATED)
        String createHousehold() {
            return "created";
        }

        @DeleteMapping("/api/v1/households/{householdId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void deleteHousehold() {
        }

        @DeleteMapping("/api/v1/households/{householdId}/appliances/{applianceId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void deleteAppliance() {
        }
    }
}
