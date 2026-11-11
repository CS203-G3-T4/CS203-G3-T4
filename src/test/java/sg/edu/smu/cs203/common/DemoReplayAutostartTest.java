package sg.edu.smu.cs203.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import sg.edu.smu.cs203.recommendation.RecommendationRecomputeJob;

class DemoReplayAutostartTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final Instant WALL = Instant.parse("2026-11-11T02:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    // 14:00 in Singapore (UTC+8).
    private static final Instant REPLAY_START = Instant.parse("2026-09-26T06:00:00Z");

    private final DemoClock clock = new DemoClock(Clock.fixed(WALL, SINGAPORE));
    private final DemoReplayRefreshService replay = mock(DemoReplayRefreshService.class);
    private final RecommendationRecomputeJob recompute = mock(RecommendationRecomputeJob.class);

    @Test
    void refreshesHouseholdsThenStartsTheConfiguredReplayDayInSingaporeTimeAsTheDemoAdmin() {
        Authentication[] during = new Authentication[1];
        doAnswer(call -> {
            during[0] = SecurityContextHolder.getContext().getAuthentication();
            clock.startAt(call.getArgument(1));
            return null;
        }).when(replay).start(any(), any());

        autostart().start();

        assertThat(during[0].getName()).isEqualTo("admin");
        assertThat(during[0].isAuthenticated()).isTrue();
        assertThat(during[0].getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        InOrder order = inOrder(recompute, replay);
        order.verify(recompute).recomputeAll(anyString());
        order.verify(replay).start(DAY, REPLAY_START);
        assertThat(clock.snapshot()).isEqualTo(new DemoClock.Snapshot(true, REPLAY_START));
    }

    @Test
    void aFailedStartIsLoggedAndLeavesTheClockOnWallTime() {
        doAnswer(call -> {
            clock.startAt(call.getArgument(1));
            throw new IllegalStateException("The replay demo household has not been seeded");
        }).when(replay).start(any(), any());

        assertThatNoException().isThrownBy(() -> autostart().start());

        assertThat(clock.snapshot()).isEqualTo(new DemoClock.Snapshot(false, WALL));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aMissingReplayDayIsLoggedInsteadOfStoppingTheApplication() {
        doThrow(new IllegalArgumentException("No replay file for 2026-09-26")).when(replay).start(any(), any());

        assertThatNoException().isThrownBy(() -> autostart().start());

        assertThat(clock.isRunning()).isFalse();
    }

    @Test
    void onlyRunsUnderTheDemoProfileWhenExplicitlyEnabled() {
        // The application context registers this conversion service; a bare runner does not.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(DemoClock.class, () -> clock)
                .withBean(DemoReplayRefreshService.class, () -> replay)
                .withBean(RecommendationRecomputeJob.class, () -> recompute)
                .withPropertyValues("wattly.demo.accounts.admin.username=admin")
                .withUserConfiguration(DemoReplayAutostart.class);

        runner.withPropertyValues("wattly.demo.replay.autostart.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DemoReplayAutostart.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
                .run(context -> assertThat(context).doesNotHaveBean(DemoReplayAutostart.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
                .withPropertyValues("wattly.demo.replay.autostart.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DemoReplayAutostart.class));
    }

    private DemoReplayAutostart autostart() {
        return new DemoReplayAutostart(clock, replay, recompute, "admin", DAY, LocalTime.of(14, 0));
    }
}
