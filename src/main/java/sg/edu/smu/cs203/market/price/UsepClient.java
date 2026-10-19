package sg.edu.smu.cs203.market.price;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class UsepClient {

    private final RestClient restClient;
    private final String url;
    private final FeedOutageSwitch outage;

    @Autowired
    public UsepClient(RestClient usepRestClient, @Value("${market.feed.url}") String url, FeedOutageSwitch outage) {
        this.restClient = usepRestClient;
        this.url = url;
        this.outage = outage;
    }

    /**
     * For callers that never simulate an outage, such as tests that fake NEMS. Keeping this two-argument
     * form means code written before the outage switch (CSDT4-26) still compiles.
     */
    public UsepClient(RestClient usepRestClient, String url) {
        this(usepRestClient, url, new FeedOutageSwitch());
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
