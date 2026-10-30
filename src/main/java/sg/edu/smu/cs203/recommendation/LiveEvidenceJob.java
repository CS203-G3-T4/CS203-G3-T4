package sg.edu.smu.cs203.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import sg.edu.smu.cs203.forecast.ForecastSaved;

@Component
public class LiveEvidenceJob {
    private static final Logger LOG=LoggerFactory.getLogger(LiveEvidenceJob.class);
    private final LiveComparisonService comparison;
    private final SavingsMonitoringService monitoring;
    public LiveEvidenceJob(LiveComparisonService comparison,SavingsMonitoringService monitoring) {this.comparison=comparison;this.monitoring=monitoring;}
    @EventListener(ApplicationReadyEvent.class)
    public void start() {comparison.initialize();tick();}
    @Order(0)
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void saved(ForecastSaved event) {
        if(!"LIVE".equals(event.mode())) return;
        try {comparison.capture(event.runId());} catch(RuntimeException e) {LOG.error("Live comparison capture failed for {}",event.runId(),e);}
        tick();
    }
    @Scheduled(cron="${forecast.live-comparison.cron:0 * * * * *}",zone="Asia/Singapore")
    public void tick() {
        try {comparison.evaluate();} catch(RuntimeException e) {LOG.error("Live comparison evaluation failed",e);}
        try {monitoring.recheck();} catch(RuntimeException e) {LOG.error("Savings start-time check failed",e);}
    }
}
