package sg.edu.smu.cs203.forecast;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static sg.edu.smu.cs203.forecast.ForecastMathTest.*;

class PythonForecastClientTest {
    static tools.jackson.databind.JsonNode syntheticResponse(String requestId) {
        var mapper=new ObjectMapper();
        var response=mapper.createObjectNode();
        response.put("schemaVersion",1).put("requestId",requestId).put("asOf",NOW.toString())
                .put("generatedAt",NOW.toString()).put("units","SGD_PER_MWH").put("modelType","AI")
                .put("modelVersion","synthetic-model").put("usableFrom",NOW.minus(Duration.ofDays(1)).toString());
        var ranking=mapper.valueToTree(List.of("B2","B1","B3"));
        response.set("baselineRanking",ranking);
        var manifest=response.putObject("manifest");
        manifest.put("version","synthetic-model").put("productionEligible",true).put("exploratory",false)
                .put("usableFrom",NOW.minus(Duration.ofDays(1)).toString())
                .put("trainingCutoff",NOW.minus(Duration.ofDays(2)).toString())
                .put("trainingDate",NOW.minus(Duration.ofDays(1)).toString())
                .put("validationEnd",NOW.minus(Duration.ofDays(1)).toString());
        manifest.set("baselineRanking",ranking);
        response.putArray("qualityFlags").add("SYNTHETIC_TEST_ONLY");
        var points=response.putArray("points");
        int h=0;
        for (var target:BaselineForecastService.targets(NOW)) {
            points.addObject().put("horizon",++h).put("targetPeriod",target.toString()).put("predictedUsep",-10.0)
                    .putNull("spikeThreshold").putNull("spikeFlag").put("assessmentAvailable",false);
        }
        return response;
    }

    @Test
    void realHttpContractAcceptsExactly24TargetsAndRejectsFutureModelAndWrongUnits() throws Exception {
        var mapper=new ObjectMapper();
        var response=syntheticResponse("test");
        PythonForecastClient.validate(response,"test",NOW);
        var altered=response.deepCopy(); ((tools.jackson.databind.node.ObjectNode)altered).put("units","CENTS_PER_KWH");
        assertThatThrownBy(() -> PythonForecastClient.validate(altered,"test",NOW)).isInstanceOf(IllegalArgumentException.class);
        var future=response.deepCopy(); ((tools.jackson.databind.node.ObjectNode)future).put("usableFrom",NOW.plusSeconds(1).toString());
        assertThatThrownBy(() -> PythonForecastClient.validate(future,"test",NOW)).isInstanceOf(IllegalArgumentException.class);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/forecast",exchange -> {
            var request=mapper.readTree(exchange.getRequestBody());
            assertThat(request.path("priceHistory").size()).isEqualTo(384);
            byte[] body=response.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client=new PythonForecastClient("http://127.0.0.1:"+server.getAddress().getPort(),Duration.ofSeconds(2));
            assertThat(client.forecast("test",NOW,rows()).path("points").size()).isEqualTo(24);
        } finally { server.stop(0); }
    }

    @Test
    void rejectsMalformedEnvelopeAndBoundsNetworkTimeout() throws Exception {
        assertThatThrownBy(() -> PythonForecastClient.validate(new ObjectMapper().readTree("{}"),"test",NOW))
                .isInstanceOf(IllegalArgumentException.class);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/forecast",exchange -> {
            try { Thread.sleep(600); exchange.sendResponseHeaders(503,-1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client=new PythonForecastClient("http://127.0.0.1:"+server.getAddress().getPort(),Duration.ofMillis(50));
            assertThatThrownBy(() -> client.forecast("test",NOW,rows()))
                    .isInstanceOf(org.springframework.web.client.RestClientException.class)
                    .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
        } finally { server.stop(0); }
    }
}
