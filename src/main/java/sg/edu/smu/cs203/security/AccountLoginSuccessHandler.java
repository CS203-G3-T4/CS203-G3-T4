package sg.edu.smu.cs203.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

final class AccountLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        if (!(authentication.getPrincipal() instanceof AccountUserDetails account)) {
            throw new IllegalStateException("Authenticated account details are required for role routing");
        }

        String destination = switch (account.role()) {
            case ADMIN -> "/households.html";
            case RESIDENT -> residentDestination(account);
        };
        redirectStrategy.sendRedirect(request, response, request.getContextPath() + destination);
    }

    private static String residentDestination(AccountUserDetails account) {
        if (account.householdId() == null) {
            throw new IllegalStateException("Resident account is missing its household");
        }
        return "/dashboard.html?household=" + account.householdId();
    }
}
