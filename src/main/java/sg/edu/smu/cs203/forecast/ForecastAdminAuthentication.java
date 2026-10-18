package sg.edu.smu.cs203.forecast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Base64;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local admin access; disabled until credentials are provided outside source control. */
@Component
public class ForecastAdminAuthentication extends OncePerRequestFilter {
    private final byte[] credentials;
    private final String username;
    public ForecastAdminAuthentication(@Value("${forecast.admin.username:}") String username,
            @Value("${forecast.admin.password:}") String password) {
        this.username=username;
        this.credentials=username.isBlank() || password.isBlank() ? null : (username+":"+password).getBytes(StandardCharsets.UTF_8);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/admin/") && !request.getRequestURI().equals("/admin-forecast.html");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) {
            String origin=request.getHeader("Origin");
            String own=request.getScheme()+"://"+request.getServerName()+
                    ((request.getServerPort()==80 || request.getServerPort()==443) ? "" : ":"+request.getServerPort());
            if (origin!=null && !origin.equals(own)) { response.sendError(403); return; }
        }
        if (request.getUserPrincipal()!=null && request.isUserInRole("ADMIN")) { chain.doFilter(request,response); return; }
        byte[] supplied=new byte[0];
        String header=request.getHeader("Authorization");
        if (header!=null && header.startsWith("Basic ")) {
            try { supplied=Base64.getDecoder().decode(header.substring(6)); } catch (IllegalArgumentException ignored) { }
        }
        if (credentials==null || !MessageDigest.isEqual(credentials,supplied)) {
            response.setHeader("Cache-Control","no-store");
            if (request.getRequestURI().endsWith(".html")) {
                response.setHeader("WWW-Authenticate","Basic realm=\"Wattly admin\", charset=\"UTF-8\"");
                response.sendError(401);
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
}
