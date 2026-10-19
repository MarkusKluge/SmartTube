package com.google.android.exoplayer2.source.sabr.manifest;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Selects equivalent Googlevideo media networks advertised by a signed SABR URL. */
public final class SabrCdnSelector {
    private static final String GOOGLEVIDEO_SUFFIX = ".googlevideo.com";
    /**
     * How long a media network that failed to answer stays skipped. Restarting the engine or
     * reloading the video builds a new manifest (and selector) from the same player response,
     * which would otherwise send the first request to the broken network again
     * (e.g. an ISP-hosted cache node that hangs on cache misses).
     */
    private static final long FAILED_NETWORK_TTL_MS = 30 * 60 * 1_000;
    private static final Map<String, Long> sFailedNetworks = new HashMap<>();
    /**
     * ISP-hosted cache networks (Google Global Cache) that are only used when nothing else is left.
     * The Deutsche Telekom node regularly stalls on uncached ranges or refuses connections, while the
     * equivalent Google networks advertised next to it work. Trying it first costs a timeout after
     * every app start.
     */
    private static final String[] DEPRIORITIZED_NETWORK_PREFIXES = {
            "sn-bvvbaxivnuxqqu5b-" // Deutsche Telekom (AS3320)
    };
    private final List<String> candidateUrls = new ArrayList<>();
    private int selectedIndex;

    public SabrCdnSelector(@Nullable String initialUrl) {
        if (initialUrl == null) {
            return;
        }

        candidateUrls.add(initialUrl);
        addAlternateUrls(initialUrl);
        moveDeprioritizedNetworksLast();
        skipFailedNetworks();
    }

    /** For logs: how many equivalent networks this selector can still fall back to. */
    public synchronized int getRemainingCandidates() {
        return Math.max(0, candidateUrls.size() - selectedIndex - 1);
    }

    /** True when the failed url is on a network this selector doesn't know (e.g. a SabrRedirect target). */
    public synchronized boolean isUnknownHost(@Nullable String url) {
        return getHost(url) != null && findCandidateIndex(url) < 0;
    }

    public synchronized @Nullable String getCurrentUrl() {
        return candidateUrls.isEmpty() ? null : candidateUrls.get(selectedIndex);
    }

    /**
     * Advances after a failure on the current network. If another track already
     * advanced, a stale failure from an earlier network remains retryable.
     */
    public synchronized boolean maybeAdvance(@Nullable String failedUrl) {
        int failedIndex = findCandidateIndex(failedUrl);

        if (failedIndex < 0 || failedIndex > selectedIndex) {
            return false;
        }

        if (failedIndex < selectedIndex) {
            return true;
        }

        if (selectedIndex + 1 >= candidateUrls.size()) {
            return false;
        }

        markFailed(getHost(failedUrl));
        selectedIndex++;
        return true;
    }

    private void moveDeprioritizedNetworksLast() {
        List<String> deprioritized = new ArrayList<>();

        for (String url : candidateUrls) {
            if (isDeprioritized(extractNetwork(getHost(url)))) {
                deprioritized.add(url);
            }
        }

        // Keep the original url if every candidate is deprioritized
        if (deprioritized.isEmpty() || deprioritized.size() == candidateUrls.size()) {
            return;
        }

        candidateUrls.removeAll(deprioritized);
        candidateUrls.addAll(deprioritized);
    }

    @VisibleForTesting
    static boolean isDeprioritized(@Nullable String network) {
        if (network == null) {
            return false;
        }

        for (String prefix : DEPRIORITIZED_NETWORK_PREFIXES) {
            if (network.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    private void skipFailedNetworks() {
        // Keep the last candidate as a fallback even if it failed too.
        while (selectedIndex + 1 < candidateUrls.size()
                && isFailed(extractNetwork(getHost(candidateUrls.get(selectedIndex))))) {
            selectedIndex++;
        }
    }

    /**
     * The url to use instead of the given googlevideo url: the same url on the first advertised
     * media network (see "mn") that didn't fail recently. Returns the url unchanged otherwise.
     * Also used by the DASH data source, so both formats share the failed networks.
     */
    public static String getHealthyUrl(String url) {
        String healthyUrl = new SabrCdnSelector(url).getCurrentUrl();
        return healthyUrl != null ? healthyUrl : url;
    }

    /** Remember that the network of this googlevideo url doesn't answer. */
    public static void markFailedUrl(@Nullable String url) {
        markFailed(getHost(url));
    }

    private static void markFailed(@Nullable String host) {
        String network = extractNetwork(host);

        if (network == null) {
            return;
        }

        synchronized (sFailedNetworks) {
            sFailedNetworks.put(network, nowMs() + FAILED_NETWORK_TTL_MS);
        }
    }

    @VisibleForTesting
    static void clearFailedNetworks() {
        synchronized (sFailedNetworks) {
            sFailedNetworks.clear();
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000; // monotonic, no Android dependency (plain JUnit tests)
    }

    private static boolean isFailed(@Nullable String network) {
        if (network == null) {
            return false;
        }

        synchronized (sFailedNetworks) {
            Long expiresAt = sFailedNetworks.get(network);

            if (expiresAt == null) {
                return false;
            }

            if (expiresAt <= nowMs()) {
                sFailedNetworks.remove(network);
                return false;
            }

            return true;
        }
    }

    private void addAlternateUrls(String initialUrl) {
        URI initialUri;

        try {
            initialUri = URI.create(initialUrl);
        } catch (IllegalArgumentException e) {
            return;
        }

        String initialHost = initialUri.getHost();
        String currentNetwork = extractNetwork(initialHost);
        String networksValue = getQueryParameter(initialUri.getRawQuery(), "mn");

        if (initialHost == null || currentNetwork == null || networksValue == null) {
            return;
        }

        String[] networks = networksValue.split(",");
        int currentNetworkIndex = -1;

        for (int i = 0; i < networks.length; i++) {
            if (currentNetwork.equals(networks[i])) {
                currentNetworkIndex = i;
                break;
            }
        }

        if (currentNetworkIndex < 0) {
            return;
        }

        for (int i = currentNetworkIndex + 1; i < networks.length; i++) {
            if (!networks[i].matches("[A-Za-z0-9-]+")) {
                continue;
            }

            String host = "rr" + (i + 1) + "---" + networks[i] + GOOGLEVIDEO_SUFFIX;
            String alternateUrl = replaceAuthority(initialUrl, initialUri, host);

            if (alternateUrl != null) {
                candidateUrls.add(alternateUrl);
            }
        }
    }

    private int findCandidateIndex(@Nullable String url) {
        String failedHost = getHost(url);

        if (failedHost == null) {
            return -1;
        }

        for (int i = 0; i < candidateUrls.size(); i++) {
            String candidateHost = getHost(candidateUrls.get(i));

            if (candidateHost != null && candidateHost.equalsIgnoreCase(failedHost)) {
                return i;
            }
        }

        return -1;
    }

    private static @Nullable String getHost(@Nullable String url) {
        if (url == null) {
            return null;
        }

        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable String extractNetwork(@Nullable String host) {
        if (host == null || !host.endsWith(GOOGLEVIDEO_SUFFIX)) {
            return null;
        }

        int networkStart = host.indexOf("---");
        int networkEnd = host.length() - GOOGLEVIDEO_SUFFIX.length();

        if (networkStart < 0 || networkStart + 3 >= networkEnd) {
            return null;
        }

        return host.substring(networkStart + 3, networkEnd);
    }

    private static @Nullable String getQueryParameter(@Nullable String rawQuery, String key) {
        if (rawQuery == null) {
            return null;
        }

        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String rawKey = separator >= 0 ? pair.substring(0, separator) : pair;

            if (key.equals(decode(rawKey))) {
                return decode(separator >= 0 ? pair.substring(separator + 1) : "");
            }
        }

        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static @Nullable String replaceAuthority(String url, URI uri, String host) {
        String oldAuthority = uri.getRawAuthority();

        if (oldAuthority == null) {
            return null;
        }

        String newAuthority = uri.getPort() >= 0 ? host + ":" + uri.getPort() : host;
        int authorityStart = url.indexOf(oldAuthority);

        if (authorityStart < 0) {
            return null;
        }

        return url.substring(0, authorityStart) + newAuthority
                + url.substring(authorityStart + oldAuthority.length());
    }
}
