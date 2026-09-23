package sg.edu.smu.cs203.market.weather;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeatherClientTest {
    @Test
    void readsV2EnvelopeAndPreservesItems() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://weather.test")).andRespond(withSuccess(
                "{\"code\":0,\"data\":{\"items\":[{\"timestamp\":\"2026-09-23T14:04:00+08:00\",\"forecasts\":[{\"area\":\"Ang Mo Kio\",\"forecast\":\"Cloudy\"}]}]}}", MediaType.APPLICATION_JSON));
        var items = new WeatherClient(builder.build(), "https://weather.test").fetch();
        assertThat(items.get(0).path("forecasts").get(0).path("area").asText()).isEqualTo("Ang Mo Kio");
        server.verify();
    }

    @Test
    void rejectsErrorEnvelopeEvenWithHttpSuccess() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://weather.test")).andRespond(withSuccess(
                "{\"code\":1,\"data\":null}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new WeatherClient(builder.build(), "https://weather.test").fetch())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
