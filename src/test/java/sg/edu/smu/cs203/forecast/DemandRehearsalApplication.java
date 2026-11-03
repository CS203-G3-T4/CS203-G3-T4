package sg.edu.smu.cs203.forecast;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;

/** Explicit test-only launcher. This class is not in the production jar. */
public final class DemandRehearsalApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(EnergyMarketServiceApplication.class,FixedTime.class).run(args);
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class FixedTime {
        @Bean @Primary
        Clock rehearsalClock(@Value("${wattly.rehearsal.as-of}") String asOf) {
            return Clock.fixed(Instant.parse(asOf),ZoneId.of("Asia/Singapore"));
        }
        @Bean
        ApplicationRunner initialForecast(ForecastJob job) { return args -> job.runOnce(); }
    }
}
