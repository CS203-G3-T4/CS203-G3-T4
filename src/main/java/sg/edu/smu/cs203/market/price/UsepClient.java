package sg.edu.smu.cs203.market.price;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class UsepClient {

    private final RestClient restClient;
    private final String url;
    private final FeedOutageSwitch outage;

    public UsepClient(RestClient usepRestClient, @Value("${market.feed.url}") String url, FeedOutageSwitch outage) {
        this.restClient = usepRestClient;
        this.url = url;
        this.outage = outage;
    }

    public UsepFeedResponse fetch() {
        if (outage.isOn()) {
            throw new SimulatedFeedOutageException();
        }
        UsepFeedResponse response = restClient.get().uri(url).retrieve().body(UsepFeedResponse.class);
        if (response == null) {
            throw new IllegalStateException("USEP feed returned an empty response");
        }
        return response;
    }
}
