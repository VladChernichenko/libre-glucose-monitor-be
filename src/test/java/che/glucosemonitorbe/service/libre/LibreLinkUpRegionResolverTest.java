package che.glucosemonitorbe.service.libre;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LibreLinkUpRegionResolverTest {

    private LibreLinkUpRegionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new LibreLinkUpRegionResolver();
    }

    @Test
    void normalizeBaseUrl_trimsAndStripsTrailingSlashes() {
        assertThat(LibreLinkUpRegionResolver.normalizeBaseUrl("  https://api-eu.libreview.io//  "))
                .isEqualTo("https://api-eu.libreview.io");
        assertThat(LibreLinkUpRegionResolver.normalizeBaseUrl(null)).isEqualTo("https://api-eu.libreview.io");
        assertThat(LibreLinkUpRegionResolver.normalizeBaseUrl("")).isEqualTo("https://api-eu.libreview.io");
    }

    @ParameterizedTest(name = "locale {0} -> {1}")
    @CsvSource({
        "fr-FR, https://api-fr.libreview.io",
        "ja-JP, https://api-jp.libreview.io",
        "zh-CN, https://api-ap.libreview.io"
    })
    void localeToBaseUrl_mapsRegionalLanguages(String locale, String expected) {
        assertThat(resolver.localeToBaseUrl(locale)).isEqualTo(expected);
    }

    @Test
    void localeToBaseUrl_returnsNullForEuUsAndBlank() {
        assertThat(resolver.localeToBaseUrl("en-GB")).isNull();
        assertThat(resolver.localeToBaseUrl("en-US")).isNull();
        assertThat(resolver.localeToBaseUrl(null)).isNull();
        assertThat(resolver.localeToBaseUrl("")).isNull();
    }

    @ParameterizedTest(name = "region {0} -> {1}")
    @CsvSource({
        "us, https://api-us.libreview.io",
        "USA, https://api-us.libreview.io",
        "fr, https://api-fr.libreview.io",
        "de, https://api-eu.libreview.io",
        "ap, https://api-ap.libreview.io",
        "jp, https://api-jp.libreview.io",
        "ae, https://api-ae.libreview.io",
        "somewhere-unknown, https://api-eu.libreview.io"
    })
    void regionBaseUrl_mapsRegionCodes(String region, String expected) {
        assertThat(resolver.regionBaseUrl(region)).isEqualTo(expected);
    }

    @Test
    void isRetryableAcrossHosts_onlyForEdgeBlocks() {
        assertThat(LibreLinkUpRegionResolver.isRetryableAcrossHosts(403)).isTrue();
        assertThat(LibreLinkUpRegionResolver.isRetryableAcrossHosts(430)).isTrue();
        assertThat(LibreLinkUpRegionResolver.isRetryableAcrossHosts(429)).isFalse();
        assertThat(LibreLinkUpRegionResolver.isRetryableAcrossHosts(500)).isFalse();
        assertThat(LibreLinkUpRegionResolver.isRetryableAcrossHosts(401)).isFalse();
    }

    @Test
    void authBaseOrder_putsLocaleHostFirstThenDefaultThenFallbacks_deduplicated() {
        List<String> order = resolver.authBaseOrder("fr-FR", "https://api-eu.libreview.io");

        // locale-matched host probed first
        assertThat(order.get(0)).isEqualTo("https://api-fr.libreview.io");
        // configured default present
        assertThat(order).contains("https://api-eu.libreview.io");
        // de-duplicated (fr appears once even though it's also a fallback)
        assertThat(order.stream().filter("https://api-fr.libreview.io"::equals).count()).isEqualTo(1L);
    }

    @Test
    void authBaseOrder_noLocale_startsWithConfiguredDefault() {
        List<String> order = resolver.authBaseOrder(null, "https://api-us.libreview.io");
        assertThat(order.get(0)).isEqualTo("https://api-us.libreview.io");
    }
}
