package sg.edu.smu.cs203.forecast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Base64;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Forecasting guard; optional Basic credentials are for explicitly enabled local demos only. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ForecastAdminAuthentication extends OncePerRequestFilter {
    private static final Set<String> ROUTES=Set.of("/admin-forecast.html",
            "/api/v1/admin/forecast-accuracy", "/api/v1/admin/forecast-baseline-validation",
            "/api/v1/admin/forecast-demand", "/api/v1/admin/forecast-training-attempts",
            "/api/v1/admin/forecast-savings-replay");
    private final byte[] credentials;
    private final String username;
    public ForecastAdminAuthentication(@Value("${forecast.admin.local-basic-enabled:false}") boolean localEnabled,
            @Value("${forecast.admin.username:}") String username,
            @Value("${forecast.admin.password:}") String password) {
        this.username=username;
        this.credentials=!localEnabled || username.isBlank() || password.isBlank() ? null : (username+":"+password).getBytes(StandardCharsets.UTF_8);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ROUTES.contains(request.getRequestURI().substring(request.getContextPath().length()));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) {
            String origin=request.getHeader("Origin");
            String own=request.getScheme()+"://"+request.getServerName()+
                    ((request.getServerPort()==80 || request.getServerPort()==443) ? "" : ":"+request.getServerPort());
            if (origin!=null && !origin.equals(own)) { response.sendError(403); return; }
        }
        response.setHeader("Cache-Control","no-store");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = authentication instanceof AnonymousAuthenticationToken;
        boolean authenticated = authentication != null && authentication.isAuthenticated() && !anonymous;
        if (authenticated) {
            if (hasAdminRole(authentication)) chain.doFilter(request,response);
            else response.sendError(403);
            return;
        }
        byte[] supplied=new byte[0];
        String header=request.getHeader("Authorization");
        if (header!=null && header.startsWith("Basic ")) {
            try { supplied=Base64.getDecoder().decode(header.substring(6)); } catch (IllegalArgumentException ignored) { }
        }
        if (credentials==null || !MessageDigest.isEqual(credentials,supplied)) {
            response.setHeader("Cache-Control","no-store");
            if (request.getRequestURI().endsWith(".html")) {
                if (credentials!=null) response.setHeader("WWW-Authenticate","Basic realm=\"Wattly local forecast admin\", charset=\"UTF-8\"");
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            } else response.sendError(403);
            return;
        }
        response.setHeader("Cache-Control","no-store");
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public Principal getUserPrincipal() { return () -> username; }
            @Override public String getRemoteUser() { return username; }
            @Override public boolean isUserInRole(String role) { return role.equals("ADMIN"); }
        },response);
    }

    private static boolean hasAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals("ROLE_ADMIN") || authority.equals("ADMIN"));
    }
}
