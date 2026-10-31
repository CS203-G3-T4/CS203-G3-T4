package sg.edu.smu.cs203.forecast;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EmcDemandSnapshotJob {
    private static final Logger LOG=LoggerFactory.getLogger(EmcDemandSnapshotJob.class);
    private final EmcDemandSnapshotImporter importer;
    private final ForecastJob forecasts;
    private final ForecastService service;
    private final Clock clock;
    private String lastFailure;
    private Instant lastWarning;
    public EmcDemandSnapshotJob(EmcDemandSnapshotImporter importer,ForecastJob forecasts,ForecastService service,Clock clock) {
        this.importer=importer;this.forecasts=forecasts;this.service=service;this.clock=clock;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron="${forecast.emc-demand.cron:10 * * * * *}",zone="Asia/Singapore")
    public synchronized void tick() {
        if(!"LIVE".equals(service.mode())) return;
        try {
            int added=importer.importLatest();
            lastFailure=null;lastWarning=null;
            // saveNewDemand has committed before trigger; no authentication impersonation.
            if(added>0) {LOG.info("Imported {} newly observed EMC demand targets",added);forecasts.trigger();}
        } catch(Exception e) {
            Instant now=clock.instant();
            String failure=e.getClass().getName()+":"+e.getMessage();
            if(!Objects.equals(failure,lastFailure) || lastWarning==null || now.isBefore(lastWarning)
                    || Duration.between(lastWarning,now).compareTo(Duration.ofMinutes(5))>=0) {
                LOG.warn("EMC demand import unavailable: {}",e.getMessage());lastFailure=failure;lastWarning=now;
            } else LOG.debug("EMC demand import still unavailable: {}",e.getMessage());
        }
    }
}
