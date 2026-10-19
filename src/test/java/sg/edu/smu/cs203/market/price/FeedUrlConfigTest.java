package sg.edu.smu.cs203.market.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * CSDT4-74: the NEMS feed URL is configurable as wattly.nems.base-url, and the older USEP_FEED_URL
 * variable keeps working, so existing setups (including the deployed app) are not broken.
 */
class FeedUrlConfigTest {

    private static final String DEFAULT_URL = "https://nems.sn.sg/api/status.json";

    private PropertySourcesPropertyResolver resolver(Map<String, Object> overrides) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        if (!overrides.isEmpty()) {
            sources.addFirst(new MapPropertySource("overrides", overrides));
        }
        for (PropertySource<?> yaml : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            sources.addLast(yaml);
        }
        return new PropertySourcesPropertyResolver(sources);
    }

    @Test
    void theFeedUrlDefaultsToTheRealNemsEndpoint() throws IOException {
        assertThat(resolver(Map.of()).getProperty("market.feed.url")).isEqualTo(DEFAULT_URL);
        assertThat(resolver(Map.of()).getProperty("wattly.nems.base-url")).isEqualTo(DEFAULT_URL);
    }

    @Test
    void theNamedPropertyOverridesTheFeedUrl() throws IOException {
        var resolver = resolver(Map.of("wattly.nems.base-url", "http://fake-nems.test/status.json"));

        assertThat(resolver.getProperty("market.feed.url")).isEqualTo("http://fake-nems.test/status.json");
    }

    @Test
    void theOlderUsepFeedUrlVariableStillWorks() throws IOException {
        var resolver = resolver(Map.of("USEP_FEED_URL", "http://old-style.test/status.json"));

        assertThat(resolver.getProperty("market.feed.url")).isEqualTo("http://old-style.test/status.json");
    }
}
