package sg.edu.smu.cs203.market.price;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class UsepClient {

    private final RestClient restClient;
    private final String url;

    public UsepClient(RestClient usepRestClient, @Value("${market.feed.url}") String url) {
        this.restClient = usepRestClient;
        this.url = url;
    }

    public UsepFeedResponse fetch() {
        UsepFeedResponse response = restClient.get().uri(url).retrieve().body(UsepFeedResponse.class);
        if (response == null) {
            throw new IllegalStateException("USEP feed returned an empty response");
        }
        return response;
    }
}
