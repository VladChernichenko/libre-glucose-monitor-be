package che.glucosemonitorbe.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Shared {@link RestTemplate} beans with explicit connect/read timeouts.
 * BE-P1-7 fix: replaces per-service {@code new RestTemplate()} with configured beans
 * so Tomcat threads are never held indefinitely by unresponsive external HTTP endpoints.
 */
@Configuration
public class RestTemplateConfig {

    /** Maximum time (ms) to establish a TCP connection to an external host. */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Maximum time (ms) to wait for the first byte of a response after connecting. */
    private static final int READ_TIMEOUT_MS = 30_000;

    @Bean
    @Primary
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    /**
     * Nightscout outbound client: same timeouts, redirects disabled so a public host
     * cannot bounce the server onto an internal IP after URL validation.
     */
    @Bean(name = "nightscoutRestTemplate")
    public RestTemplate nightscoutRestTemplate() {
        NoRedirectClientHttpRequestFactory factory = new NoRedirectClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }
}
