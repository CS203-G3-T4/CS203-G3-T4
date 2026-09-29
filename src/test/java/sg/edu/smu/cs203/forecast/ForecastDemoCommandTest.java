package sg.edu.smu.cs203.forecast;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForecastDemoCommandTest {
    @Test
    void localCommandRunsExistingJobAndClosesOnlyItsOwnContext() throws Exception {
        var job=mock(ForecastJob.class);
        var ingestion=mock(sg.edu.smu.cs203.market.price.MarketIngestionService.class);
        var context=mock(ConfigurableApplicationContext.class);
        var configuration=new ForecastConfiguration();
        assertThatThrownBy(() -> configuration.forecastDemoOnce(job,ingestion,context,"servlet").run(null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(job,ingestion,context);
        configuration.forecastDemoOnce(job,ingestion,context,"none").run(null);
        var order=inOrder(ingestion,job,context);
        order.verify(ingestion).poll(); order.verify(job).runOnce(); order.verify(context).close();
    }
}
