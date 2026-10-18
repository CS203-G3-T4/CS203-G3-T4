package sg.edu.smu.cs203.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;

@WebMvcTest(AccountLoginFlowTest.TestEndpoint.class)
@ContextConfiguration(classes = EnergyMarketServiceApplication.class)
@ImportAutoConfiguration(ServletWebSecurityAutoConfiguration.class)
@Import({SecurityConfiguration.class, AccountUserDetailsService.class,
        AccountLoginFlowTest.TestEndpoint.class})
class AccountLoginFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserAccountRepository accounts;

    @BeforeEach
    void setUpAccounts() {
        when(accounts.findByUsername("resident")).thenReturn(Optional.of(
                account("resident", AccountRole.RESIDENT, 42L)));
        when(accounts.findByUsername("admin")).thenReturn(Optional.of(
                account("admin", AccountRole.ADMIN, null)));
    }

    @Test
    void loginPageHasAccessibleAccountFieldsAndGenericRetryFeedback() throws Exception {
        String html = new ClassPathResource("static/login.html")
                .getContentAsString(StandardCharsets.UTF_8);

        mvc.perform(get("/login.html")).andExpect(status().isOk());
        assertTrue(html.contains("id=\"username\" name=\"username\" type=\"text\""));
        assertTrue(html.contains("id=\"password\" name=\"password\" type=\"password\""));
        assertTrue(html.contains(
                "Username or password is incorrect. Please try again."));
        assertTrue(html.contains(
                "loginError.hidden = !new URLSearchParams(window.location.search).has('error')"));
        assertFalse(html.contains("name=\"role\""));
    }

    @Test
    void loginRejectsMissingCsrfToken() throws Exception {
        mvc.perform(post("/login")
                        .param("username", "resident")
                        .param("password", "secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCredentialsReturnToLoginWithRetryableGenericError() throws Exception {
        for (String username : new String[] {"resident", "unknown"}) {
            mvc.perform(post("/login")
                            .param("username", username)
                            .param("password", "incorrect")
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login.html?error"));
        }
    }

    @Test
    void residentsLandOnTheirAccountHousehold() throws Exception {
        mvc.perform(post("/login")
                        .param("username", "resident")
                        .param("password", "secret")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard.html?household=42"));
    }

    @Test
    void adminsLandOnHouseholdAdministration() throws Exception {
        mvc.perform(post("/login")
                        .param("username", "admin")
                        .param("password", "secret")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/households.html"));
    }

    private UserAccount account(String username, AccountRole role, Long householdId) {
        return new UserAccount(username, passwordEncoder.encode("secret"), role, householdId, false);
    }

    @RestController
    static class TestEndpoint {

        @GetMapping("/test")
        String test() {
            return "ok";
        }
    }
}
