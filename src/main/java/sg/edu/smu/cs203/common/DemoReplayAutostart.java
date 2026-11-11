package sg.edu.smu.cs203.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import sg.edu.smu.cs203.recommendation.RecommendationRecomputeJob;

/**
 * Local marker demo only: starts the replay on startup, as if the seeded demo admin pressed
 * Start on /admin-demo.html. Requires the demo profile and wattly.demo.replay.autostart.enabled=true.
 */
@Component
@Profile("demo")
@ConditionalOnProperty(name = "wattly.demo.replay.autostart.enabled", havingValue = "true")
class DemoReplayAutostart {

    private static final Logger LOG = LoggerFactory.getLogger(DemoReplayAutostart.class);
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    private final DemoClock clock;
    private final DemoReplayRefreshService replay;
    private final RecommendationRecomputeJob recompute;
    private final String adminUsername;
    private final LocalDate date;
    private final LocalTime time;

    DemoReplayAutostart(DemoClock clock, DemoReplayRefreshService replay, RecommendationRecomputeJob recompute,
                        @Value("${wattly.demo.accounts.admin.username}") String adminUsername,
                        @Value("${wattly.demo.replay.autostart.date:2026-09-26}") LocalDate date,
                        @Value("${wattly.demo.replay.autostart.time:14:00}") LocalTime time) {
        this.clock = clock;
        this.replay = replay;
        this.recompute = recompute;
        this.adminUsername = adminUsername;
        this.date = date;
        this.time = time;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // Recompute is paused while the replay runs, so refresh every household first,
        // whichever order Spring runs the startup listeners in.
        recompute.recomputeAll("startup before demo replay");
        Instant start = date.atTime(time).atZone(SINGAPORE).toInstant();
        // The replay refreshes the replay household's suggestions, which needs an admin account.
        // Act as the demo admin (seeded under the same profile) only for this call.
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext admin = SecurityContextHolder.createEmptyContext();
        admin.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                adminUsername, null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        SecurityContextHolder.setContext(admin);
        try {
            clock.transition(() -> {
                replay.start(date, start);
                return null;
            });
            LOG.info("Demo replay started at {} {} (Asia/Singapore) as '{}'", date, time, adminUsername);
        } catch (RuntimeException failure) {
            LOG.error("Demo replay autostart failed for {} {}; start it from /admin-demo.html instead",
                    date, time, failure);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
