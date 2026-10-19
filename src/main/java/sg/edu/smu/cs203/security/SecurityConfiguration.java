package sg.edu.smu.cs203.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/login", "/api/v1/csrf",
                                "/css/**", "/js/**", "/images/**", "/favicon.ico")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**", "/api/v1/demo/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/households")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/households/*")
                        .hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.FORBIDDEN), adminApiMatcher()))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .formLogin(form -> form
                        .loginPage("/")
                        .loginProcessingUrl("/login")
                        .failureUrl("/?error")
                        .successHandler(new AccountLoginSuccessHandler())
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/?logout"));
        return http.build();
    }

    private static RequestMatcher adminApiMatcher() {
        return request -> {
            String path = request.getServletPath();
            return path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/")
                    || path.equals("/api/v1/demo") || path.startsWith("/api/v1/demo/")
                    || request.getMethod().equals("POST") && path.equals("/api/v1/households")
                    || request.getMethod().equals("DELETE")
                    && path.matches("/api/v1/households/[^/]+");
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
