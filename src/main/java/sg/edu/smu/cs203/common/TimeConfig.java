package sg.edu.smu.cs203.common;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class TimeConfig {
    @Bean
    @Primary
    public DemoClock clock() {
        return new DemoClock(Clock.system(ZoneId.of("Asia/Singapore")));
    }

    @Bean
    @Qualifier("wallClock")
    public Clock wallClock() {
        return Clock.system(ZoneId.of("Asia/Singapore"));
    }
}
