package che.glucosemonitorbe.service.libre;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * LibreLinkUp regional host routing (BE-M5 decomposition).
 *
 * <p>LibreLinkUp shards accounts across regional hosts; hitting the wrong host rejects valid
 * credentials. This resolver maps locales/regions to hosts and builds the ordered list of hosts to
 * probe during authentication.
 */
@Component
public class LibreLinkUpRegionResolver {

    private static final Logger logger = LoggerFactory.getLogger(LibreLinkUpRegionResolver.class);

    private static final String EU = "https://api-eu.libreview.io";

    /**
     * When a host returns HTTP 403 or 430 (edge/WAF), try these after the configured base.
     * Not used for 429 (rate limit) — each host hit counts toward Cloudflare 1015.
     */
    private static final String[] FALLBACK_BASES = {
            "https://api-eu.libreview.io",
            "https://api-fr.libreview.io",
            "https://api-us.libreview.io",
            "https://api-ap.libreview.io",
            "https://api-jp.libreview.io",
            "https://api-ae.libreview.io"
    };

    public static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return EU;
        }
        return url.strip().replaceAll("/+$", "");
    }

    /** Only 403/430 warrant trying another host; never retry on 429. */
    public static boolean isRetryableAcrossHosts(int httpStatus) {
        return httpStatus == 403 || httpStatus == 430;
    }

    /**
     * Maps an IETF locale tag to the most likely LibreLinkUp regional base URL, or {@code null} when
     * EU/US (covered by the configured default + fallback list).
     */
    public String localeToBaseUrl(String locale) {
        if (locale == null || locale.isBlank()) {
            return null;
        }
        String lang = locale.toLowerCase(Locale.ROOT).split("[_\\-]")[0];
        switch (lang) {
            case "fr": return "https://api-fr.libreview.io";
            case "ja": return "https://api-jp.libreview.io";
            case "zh": return "https://api-ap.libreview.io";
            default:   return null;
        }
    }

    /** Region code (from a login {@code redirect} response) to its base URL. */
    public String regionBaseUrl(String region) {
        switch (region.toLowerCase()) {
            case "us":
            case "usa":
                return "https://api-us.libreview.io";
            case "fr":
                return "https://api-fr.libreview.io";
            case "eu":
            case "de":
            case "uk":
            case "gb":
                return EU;
            case "ap":
            case "au":
            case "asia":
                return "https://api-ap.libreview.io";
            case "ae":
                return "https://api-ae.libreview.io";
            case "jp":
                return "https://api-jp.libreview.io";
            default:
                logger.warn("Unknown region {}, defaulting to EU", region);
                return EU;
        }
    }

    /**
     * Ordered, de-duplicated base URLs to try when authenticating: the locale-matched host first
     * (api-eu rejects e.g. French accounts outright), then the configured default, then the fallbacks.
     */
    public List<String> authBaseOrder(String locale, String normalizedDefault) {
        LinkedHashSet<String> bases = new LinkedHashSet<>();
        String localeBase = localeToBaseUrl(locale);
        if (localeBase != null) {
            bases.add(localeBase);
        }
        bases.add(normalizedDefault);
        bases.addAll(Arrays.asList(FALLBACK_BASES));
        return new ArrayList<>(bases);
    }
}
