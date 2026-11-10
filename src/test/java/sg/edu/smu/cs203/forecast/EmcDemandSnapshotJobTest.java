package sg.edu.smu.cs203.forecast;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmcDemandSnapshotJobTest {
    @Test void repeatedFailureWarnsOncePerFiveMinutesAndRecoveryResetsIt() throws Exception {
        var importer=mock(EmcDemandSnapshotImporter.class);var forecasts=mock(ForecastJob.class);
        var service=mock(ForecastService.class);when(service.mode()).thenReturn("LIVE");
        var now=new AtomicReference<>(Instant.parse("2026-10-30T00:00:00Z"));
        Clock clock=new Clock() {public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};
        when(importer.importLatest()).thenThrow(new IllegalArgumentException("Stale or future EMC receipt"));
        var logger=(Logger)LoggerFactory.getLogger(EmcDemandSnapshotJob.class);
        var logs=new ListAppender<ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {
            var job=new EmcDemandSnapshotJob(importer,forecasts,service,clock);
            job.tick();now.set(now.get().plusSeconds(60));job.tick();
            assertThat(logs.list.stream().filter(e->e.getLevel()==ch.qos.logback.classic.Level.WARN).count()).isEqualTo(1);
            now.set(now.get().plusSeconds(240));job.tick();
            assertThat(logs.list.stream().filter(e->e.getLevel()==ch.qos.logback.classic.Level.WARN).count()).isEqualTo(2);
            doReturn(1).when(importer).importLatest();job.tick();verify(forecasts).trigger();
            when(importer.importLatest()).thenThrow(new IllegalArgumentException("Stale or future EMC receipt"));job.tick();
            assertThat(logs.list.stream().filter(e->e.getLevel()==ch.qos.logback.classic.Level.WARN).count()).isEqualTo(3);
        } finally {logger.detachAppender(logs);logs.stop();}
    }
}
