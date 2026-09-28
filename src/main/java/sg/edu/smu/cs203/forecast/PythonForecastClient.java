package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import sg.edu.smu.cs203.market.price.MarketRevision;
import tools.jackson.databind.JsonNode;

@Component
public class PythonForecastClient {
    private final RestClient client;
    private final String url;
    public PythonForecastClient(@Value("${forecast.python-url:http://127.0.0.1:8001}") String url,
                                @Value("${forecast.python-timeout:5s}") Duration timeout) {
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout); factory.setReadTimeout(timeout);
        this.client=RestClient.builder().requestFactory(factory).build(); this.url=url;
    }
    public JsonNode forecast(String requestId, Instant asOf, List<MarketRevision> history) {
        List<Map<String,Object>> prices=history.stream().map(r -> Map.<String,Object>of(
                "periodStart",r.periodStart(),"sourceUpdatedAt",r.sourceUpdatedAt(),"availableAt",r.availableAt(),"usep",r.usep())).toList();
        JsonNode result=client.post().uri(url+"/forecast").body(Map.of("schemaVersion",1,"requestId",requestId,
                "asOf",asOf,"intervalMinutes",30,"horizonCount",24,"mappingVerified",true,"priceHistory",prices,
                "weather",List.of())).retrieve().body(JsonNode.class);
        validate(result,requestId,asOf);
        return result;
    }
    static void validate(JsonNode response,String requestId,Instant asOf) {
        if (response==null || response.path("schemaVersion").asInt()!=1 || !response.path("requestId").asText().equals(requestId)
                || !"SGD_PER_MWH".equals(response.path("units").asText()) || !"AI".equals(response.path("modelType").asText())
                || !Instant.parse(response.path("asOf").asText()).equals(asOf)
                || response.path("modelVersion").asText().isBlank()
                || Instant.parse(response.path("usableFrom").asText()).isAfter(asOf)
                || !response.path("manifest").path("version").asText().equals(response.path("modelVersion").asText())
                || !response.path("manifest").path("productionEligible").asBoolean()
                || response.path("manifest").path("exploratory").asBoolean()
                || !response.path("manifest").path("usableFrom").equals(response.path("usableFrom"))
                || Instant.parse(response.path("manifest").path("trainingCutoff").asText()).isAfter(asOf)
                || Instant.parse(response.path("manifest").path("trainingDate").asText()).isAfter(asOf)
                || Instant.parse(response.path("manifest").path("validationEnd").asText()).isAfter(asOf)
                || !response.path("manifest").path("baselineRanking").equals(response.path("baselineRanking"))
                || !response.path("points").isArray() || response.path("points").size()!=24) {
            throw new IllegalArgumentException("Invalid Python forecast envelope");
        }
        List<Instant> targets=BaselineForecastService.targets(asOf);
        for (int i=0;i<24;i++) {
            JsonNode point=response.path("points").get(i);
            if (point.path("horizon").asInt()!=i+1 || !Instant.parse(point.path("targetPeriod").asText()).equals(targets.get(i))
                    || !point.path("predictedUsep").isNumber() || !Double.isFinite(point.path("predictedUsep").asDouble()))
                throw new IllegalArgumentException("Invalid Python target/price");
        }
        JsonNode ranking=response.path("baselineRanking");
        if (!ranking.isArray() || ranking.size()!=3) throw new IllegalArgumentException("Missing validation ranking");
        java.util.Set<String> unique=new java.util.HashSet<>();
        for (JsonNode name : ranking) unique.add(name.asText());
        if (!unique.equals(java.util.Set.of("B1","B2","B3"))) throw new IllegalArgumentException("Invalid validation ranking");
    }
}
