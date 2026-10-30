package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    final ForecastOperationsRepository operations=mock(ForecastOperationsRepository.class);
    final PythonForecastClient python=mock(PythonForecastClient.class);

    ForecastService service(String mode,String evidence) {
        return new ForecastService(saved,history,ingestion,CLOCK,Duration.ofMinutes(40),mode,evidence);
    }
    ForecastJob job(ForecastService service) {
        return new ForecastJob(history,saved,new BaselineForecastService(),
                new CurrentAssessmentService(history,CLOCK,service,14,BigDecimal.valueOf(3),BigDecimal.ONE,BigDecimal.ONE),
                python,service,new ObjectMapper(),CLOCK,operations);
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
    void mismatchedSpikeRuleRejectsAiAndRetainsPreviouslyValidatedBaselineRanking() {
        when(history.asOf(NOW)).thenReturn(rows());
        when(saved.baselineRanking(NOW,"REPLAY")).thenReturn(List.of("B3","B1","B2"));
        when(python.forecast(anyString(),eq(NOW),anyList())).thenAnswer(call -> {
            var response=PythonForecastClientTest.syntheticResponse(call.getArgument(0));
            ((tools.jackson.databind.node.ObjectNode)response.path("manifest").path("spikeConfig")).put("k",4);
            return response;
        });
        var job=job(service("REPLAY","synthetic"));
        try { job.runOnce(); } finally { job.close(); }
        var capture=ArgumentCaptor.forClass(ForecastTypes.Run.class);
        verify(saved).save(capture.capture(),anyString(),argThat(candidates -> !candidates.containsKey("AI")),isNull());
        assertThat(capture.getValue().selectedModel()).isEqualTo("B3");
        assertThat(capture.getValue().fallbackReason()).isEqualTo("PYTHON_UNAVAILABLE_OR_INVALID");
        assertThat(capture.getValue().qualityFlags()).doesNotContain("SYNTHETIC_TEST_ONLY","BASELINE_RANKING_UNRANKED");
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
    @ParameterizedTest
    @ValueSource(ints={3,4})
    void solarRevisionsAreSavedAndChangeRunIdentityWithTheSamePrices(int featureSchema) {
        when(history.asOf(NOW)).thenReturn(rows());
        when(saved.baselineRanking(NOW,"REPLAY")).thenReturn(List.of());
        var first=PythonForecastClientTest.solarResponse("solar");
        ((tools.jackson.databind.node.ObjectNode)first.path("manifest")).put("featureSchemaVersion",featureSchema);
        var second=first.deepCopy();
        ((tools.jackson.databind.node.ObjectNode)second.path("inputEvidence")).put("snapshotSha256","b".repeat(64));
        ((tools.jackson.databind.node.ObjectNode)second.path("inputEvidence").path("periods").get(0)).put("solarMw",200);
        when(python.forecast(anyString(),eq(NOW),anyList())).thenReturn(first,first,second);
        var job=job(service("REPLAY","synthetic"));
        try { job.runOnce();job.runOnce();job.runOnce(); } finally { job.close(); }
        var runs=ArgumentCaptor.forClass(ForecastTypes.Run.class);
        var snapshots=ArgumentCaptor.forClass(String.class);
        verify(saved,times(3)).save(runs.capture(),snapshots.capture(),anyMap(),any());
        assertThat(runs.getAllValues().get(0).modelType()).isEqualTo("AI");
        assertThat(runs.getAllValues().get(0).id()).isEqualTo(runs.getAllValues().get(1).id());
        assertThat(runs.getAllValues().get(2).id()).isNotEqualTo(runs.getAllValues().get(0).id());
        assertThat(runs.getAllValues().get(2).inputRevision()).isNotEqualTo(runs.getAllValues().get(0).inputRevision());
        assertThat(snapshots.getAllValues().get(2)).contains("solarInputs","solarMw","availableAt","marketInputs");
    }

}
