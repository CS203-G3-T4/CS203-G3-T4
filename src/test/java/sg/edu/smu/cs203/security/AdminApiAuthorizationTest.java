package sg.edu.smu.cs203.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;

@WebMvcTest(AdminApiAuthorizationTest.AdminApi.class)
@ContextConfiguration(classes = EnergyMarketServiceApplication.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({SecurityConfiguration.class, AdminApiAuthorizationTest.AdminApi.class})
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

    @RestController
    static class AdminApi {

        @GetMapping({"/api/v1/admin", "/api/v1/admin/test", "/api/v1/demo", "/api/v1/demo/test"})
        String adminEndpoint() {
            return "admin";
        }
    }
}
