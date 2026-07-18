package che.glucosemonitorbe.nightscout;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF guard for user-supplied Nightscout base URLs.
 *
 * <p>Rejects non-http(s) schemes, credentials-in-URL, unresolved hosts, and
 * private / link-local / loopback / metadata addresses (including after DNS resolve).
 * Call this both when saving config and immediately before each outbound fetch.
 */
public final class NightscoutUrlValidator {

    private NightscoutUrlValidator() {}

    /**
     * @throws IllegalArgumentException if the URL is missing, malformed, or targets a disallowed host
     */
    public static void validateSafeForOutboundFetch(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Nightscout URL is required");
        }

        String trimmed = rawUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Nightscout URL is not a valid URI");
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("Nightscout URL must start with http:// or https://");
        }
        String schemeLower = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(schemeLower) && !"https".equals(schemeLower)) {
            throw new IllegalArgumentException("Nightscout URL must start with http:// or https://");
        }

        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
            throw new IllegalArgumentException("Nightscout URL must not contain credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Nightscout URL must include a host");
        }

        String hostLower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(hostLower)
                || "metadata.google.internal".equals(hostLower)
                || hostLower.endsWith(".localhost")) {
            throw new IllegalArgumentException("Nightscout URL host is not allowed");
        }

        // Literal IPv4/IPv6 in the URL - check without DNS.
        if (isLiteralIp(host) && isBlockedAddress(host)) {
            throw new IllegalArgumentException("Nightscout URL must not target a private or local address");
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Nightscout URL host could not be resolved");
        }
        if (resolved.length == 0) {
            throw new IllegalArgumentException("Nightscout URL host could not be resolved");
        }
        for (InetAddress address : resolved) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("Nightscout URL must not target a private or local address");
            }
        }
    }

    /** Returns true if validation would succeed (for probe responses that prefer ok=false over throw). */
    public static boolean isSafeForOutboundFetch(String rawUrl) {
        try {
            validateSafeForOutboundFetch(rawUrl);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String validationErrorMessage(String rawUrl) {
        try {
            validateSafeForOutboundFetch(rawUrl);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static boolean isLiteralIp(String host) {
        // InetAddress.getByName accepts hostnames; detect literals roughly.
        if (host.indexOf(':') >= 0) {
            return true; // IPv6
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(c == '.' || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return host.indexOf('.') >= 0;
    }

    private static boolean isBlockedAddress(String hostOrIp) {
        try {
            return isBlockedAddress(InetAddress.getByName(hostOrIp));
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNat(address)
                || isDocumentationOrReserved(address);
    }

    /** 100.64.0.0/10 shared address space (CGNAT) - often used for cloud metadata paths. */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != 4) {
            return false;
        }
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first == 100 && second >= 64 && second <= 127;
    }

    /** 0.0.0.0/8, 169.254.0.0/16 already covered by link-local; block TEST-NET / docs ranges. */
    private static boolean isDocumentationOrReserved(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != 4) {
            // Unique-local IPv6 fc00::/7
            int first = b[0] & 0xff;
            return (first & 0xfe) == 0xfc;
        }
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        // 0.0.0.0/8
        if (a == 0) {
            return true;
        }
        // TEST-NET-1 192.0.2.0/24, TEST-NET-2 198.51.100.0/24, TEST-NET-3 203.0.113.0/24
        if (a == 192 && c == 0 && (b[2] & 0xff) == 2) {
            return true;
        }
        if (a == 198 && c == 51 && (b[2] & 0xff) == 100) {
            return true;
        }
        if (a == 203 && c == 0 && (b[2] & 0xff) == 113) {
            return true;
        }
        // AWS/GCP style link-local metadata is 169.254.169.254 - covered by isLinkLocalAddress
        return false;
    }
}
