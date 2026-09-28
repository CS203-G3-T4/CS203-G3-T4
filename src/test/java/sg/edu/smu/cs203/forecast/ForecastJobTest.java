package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import sg.edu.smu.cs203.market.price.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static sg.edu.smu.cs203.forecast.ForecastMathTest.*;

class ForecastJobTest {
    final MarketHistoryRepository history=mock(MarketHistoryRepository.class);
    final ForecastRepository saved=mock(ForecastRepository.class);
    final IngestionRunRepository ingestion=mock(IngestionRunRepository.class);
    final PythonForecastClient python=mock(PythonForecastClient.class);

    ForecastService service(String mode,String evidence) {
        return new ForecastService(saved,history,ingestion,CLOCK,Duration.ofMinutes(40),mode,evidence);
    }
    ForecastJob job(ForecastService service) {
        return new ForecastJob(history,saved,new BaselineForecastService(),
                new CurrentAssessmentService(history,CLOCK,service,14,BigDecimal.valueOf(3),BigDecimal.ONE,BigDecimal.ONE),
                python,service,new ObjectMapper(),CLOCK);
    }
    @Test
    void pythonDownUsesJavaBaselineAndRepeatedInputKeepsSameRunIdentity() {
        when(history.asOf(NOW)).thenReturn(rows()); when(saved.baselineRanking(eq(NOW),anyString())).thenReturn(List.of());
        when(python.forecast(anyString(),eq(NOW),anyList())).thenThrow(new IllegalStateException("down"));
        var job=job(service("REPLAY","synthetic test evidence"));
        try { job.runOnce(); job.runOnce(); } finally { job.close(); }
        var capture=ArgumentCaptor.forClass(ForecastTypes.Run.class);
        verify(saved,times(2)).save(capture.capture(),anyString(),anyMap(),isNull());
        var run=capture.getAllValues().getFirst();
        assertThat(run.id()).isEqualTo(capture.getAllValues().getLast().id());
        assertThat(run.modelType()).isEqualTo("BASELINE"); assertThat(run.selectedModel()).isEqualTo("B1");
        assertThat(run.fallbackReason()).isEqualTo("PYTHON_UNAVAILABLE_OR_INVALID");
        assertThat(run.points()).hasSize(24); assertThat(run.qualityFlags()).contains("BASELINE_RANKING_UNRANKED");
    }
    @Test
    void staleFeedCannotGenerateActionableNewTargets() {
        when(history.asOf(NOW)).thenReturn(rows()); when(saved.baselineRanking(eq(NOW),anyString())).thenReturn(List.of());
        when(ingestion.findLatest()).thenReturn(Optional.of(new IngestionRun(NOW,NOW,IngestionStatus.FAILURE,null,"down")));
        var job=job(service("LIVE","verified test"));
        try { job.runOnce(); } finally { job.close(); }
        var capture=ArgumentCaptor.forClass(ForecastTypes.Run.class);
        verify(saved).save(capture.capture(),anyString(),anyMap(),isNull());
        assertThat(capture.getValue().status()).isEqualTo("UNAVAILABLE");
        assertThat(capture.getValue().stale()).isTrue(); verifyNoInteractions(python);
    }
    @Test
    void mappingUnverifiedProducesLabelledNonActionablePreview() {
        when(history.asOf(NOW)).thenReturn(rows()); when(saved.baselineRanking(eq(NOW),anyString())).thenReturn(List.of());
        var service=service("REPLAY",""); var job=job(service);
        try { job.runOnce(); } finally { job.close(); }
        var capture=ArgumentCaptor.forClass(ForecastTypes.Run.class);
        verify(saved).save(capture.capture(),anyString(),anyMap(),isNull());
        when(saved.latest("REPLAY",NOW)).thenReturn(Optional.of(capture.getValue()));
        assertThat(service.latest().available()).isTrue(); assertThat(service.latest().actionable()).isFalse();
        verifyNoInteractions(python);
    }

    @Test
    void updatesDuringInflightRunAreCoalescedAndProcessedAfterIt() throws Exception {
        when(history.asOf(NOW)).thenReturn(rows()); when(saved.baselineRanking(eq(NOW),anyString())).thenReturn(List.of());
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var completed=new java.util.concurrent.CountDownLatch(2);
        when(python.forecast(anyString(),any(),anyList())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(2,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            throw new IllegalStateException("down");
        });
        when(saved.save(any(),anyString(),anyMap(),isNull())).thenAnswer(invocation -> { completed.countDown(); return true; });
        var job=job(service("REPLAY","synthetic"));
        try {
            job.trigger();
            assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            for (int i=0;i<20;i++) job.trigger();
            release.countDown();
            assertThat(completed.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            verify(history,times(2)).asOf(NOW);
        } finally { release.countDown(); job.close(); }
    }
}
