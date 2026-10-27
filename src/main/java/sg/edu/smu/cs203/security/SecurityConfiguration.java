package sg.edu.smu.cs203.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import sg.edu.smu.cs203.forecast.ForecastAdminAuthentication;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ForecastAdminAuthentication forecastAdminAuthentication)
            throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/index.html", "/login", "/api/v1/csrf",
                                "/css/**", "/js/**", "/images/**", "/favicon.ico")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/forecast.html", "/api/v1/forecast/**")
                        .permitAll()
                        .requestMatchers("/admin-forecast.html",
                                "/api/v1/admin/forecast-accuracy",
                                "/api/v1/admin/forecast-baseline-validation",
                                "/api/v1/admin/forecast-demand",
                                "/api/v1/admin/forecast-training-attempts",
                                "/api/v1/admin/forecast-savings-replay")
                        .permitAll()
                        .requestMatchers("/admin-demo.html")
                        .hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**", "/api/v1/demo/**",
                                "/admin-feed.html", "/admin-overview.html")
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
        http.addFilterBefore(forecastAdminAuthentication, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    FilterRegistrationBean<ForecastAdminAuthentication> disableForecastAdminServletRegistration(
            ForecastAdminAuthentication forecastAdminAuthentication) {
        FilterRegistrationBean<ForecastAdminAuthentication> registration =
                new FilterRegistrationBean<>(forecastAdminAuthentication);
        registration.setEnabled(false);
        return registration;
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
