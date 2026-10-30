package sg.edu.smu.cs203.forecast;

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
    public EmcDemandSnapshotJob(EmcDemandSnapshotImporter importer,ForecastJob forecasts,ForecastService service) {
        this.importer=importer;this.forecasts=forecasts;this.service=service;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron="${forecast.emc-demand.cron:10 * * * * *}",zone="Asia/Singapore")
    public void tick() {
        if(!"LIVE".equals(service.mode())) return;
        try {
            int added=importer.importLatest();
            // saveNewDemand has committed before trigger; no authentication impersonation.
            if(added>0) {LOG.info("Imported {} newly observed EMC demand targets",added);forecasts.trigger();}
        } catch(Exception e) {LOG.warn("EMC demand import unavailable: {}",e.getMessage());}
    }
}
