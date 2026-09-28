package sg.edu.smu.cs203.market.weather;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Component
public class WeatherClient {
    private final RestClient client;
    private final String url;

    public WeatherClient(RestClient usepRestClient, @Value("${weather.feed.url}") String url) {
        this.client = usepRestClient;
        this.url = url;
    }

    public JsonNode fetch() {
        JsonNode response = client.get().uri(url).retrieve().body(JsonNode.class);
        if (response == null || !response.path("code").isIntegralNumber()
                || response.path("code").asInt() != 0
                || !response.path("data").path("items").isArray()
                || response.path("data").path("items").isEmpty()) {
            throw new IllegalArgumentException("NEA returned an empty or unsuccessful forecast response");
        }
        return response.path("data").path("items");
    }
}
