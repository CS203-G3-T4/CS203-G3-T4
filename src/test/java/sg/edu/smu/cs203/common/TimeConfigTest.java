package sg.edu.smu.cs203.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigTest {
    @Test
    void sharedClockUsesSingaporeZone() {
        assertThat(new TimeConfig().clock().getZone()).isEqualTo(ZoneId.of("Asia/Singapore"));
    }

    @Test
    void testPrimaryClockOverridesApplicationDefault() {
        Clock testClock = Clock.systemUTC();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("testClock", Clock.class, () -> testClock, definition -> definition.setPrimary(true));
            context.register(TimeConfig.class);
            context.refresh();

            assertThat(context.getBean(Clock.class)).isSameAs(testClock);
        }
    }

    @Test
    void demoClockRemainsTheApplicationDefault() {
        try (var context = new AnnotationConfigApplicationContext(TimeConfig.class)) {
            assertThat(context.getBean(Clock.class)).isInstanceOf(DemoClock.class);
        }
    }
}
