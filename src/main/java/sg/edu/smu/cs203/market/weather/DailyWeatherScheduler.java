package sg.edu.smu.cs203.market.weather;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "weather.daily.enabled", havingValue = "true", matchIfMissing = true)
public class DailyWeatherScheduler {
    private final DailyWeatherService service;

    public DailyWeatherScheduler(DailyWeatherService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        service.poll();
    }

    @Scheduled(cron = "${weather.daily.cron}", zone = "Asia/Singapore")
    public void onSchedule() {
        service.poll();
    }
}
