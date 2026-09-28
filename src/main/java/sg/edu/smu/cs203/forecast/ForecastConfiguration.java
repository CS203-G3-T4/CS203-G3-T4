package sg.edu.smu.cs203.forecast;

import java.nio.file.Path;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sg.edu.smu.cs203.market.price.MarketHistoryImport;
import sg.edu.smu.cs203.market.price.MarketObservationSaved;

@Configuration
public class ForecastConfiguration {
    @Bean
    ApplicationRunner forecastImport(MarketHistoryImport importer,ApplicationEventPublisher events,
            @Value("${forecast.import-file:}") String file) {
        return args -> {
            if (!file.isBlank()) {
                int imported=importer.importFile(Path.of(file));
                LoggerFactory.getLogger(ForecastConfiguration.class).info("Imported {} new/earlier canonical revisions",imported);
                if (imported>0) events.publishEvent(new MarketObservationSaved());
            }
        };
    }
}
