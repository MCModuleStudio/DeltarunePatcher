package org.mcmodule.util.downloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class IEProxySupport {
    private static final String INTERNET_SETTINGS = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    private static ProxySelector proxySelector;

    private IEProxySupport() {
    }

    public static synchronized ProxySelector getProxySelector() {
        if (proxySelector == null) {
            System.setProperty("java.net.useSystemProxies", "true");
            ProxySelector fallback = ProxySelector.getDefault();
            if (fallback == null) {
                fallback = new NoProxySelector();
            }
            proxySelector = createProxySelector(fallback);
        }
        return proxySelector;
    }

    public static synchronized void install() {
        ProxySelector selector = getProxySelector();
        if (selector != null) {
            ProxySelector.setDefault(selector);
        }
    }

    private static ProxySelector createProxySelector(ProxySelector fallback) {
        IEProxyConfig config = readIEProxyConfig();
        if (config == null || !config.enabled || config.proxyServer == null || config.proxyServer.isEmpty()) {
            return fallback;
        }
        return new IEProxySelector(config, fallback);
    }

    private static IEProxyConfig readIEProxyConfig() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        try {
            IEProxyConfig config = new IEProxyConfig();
            config.enabled = parseRegistryBoolean(queryRegistryValue("ProxyEnable"));
            config.proxyServer = valueOrEmpty(queryRegistryValue("ProxyServer"));
            config.proxyOverride = valueOrEmpty(queryRegistryValue("ProxyOverride"));
            return config;
        } catch (IOException e) {
            return null;
        }
    }

    private static String queryRegistryValue(String valueName) throws IOException {
        Process process = new ProcessBuilder("reg", "query", INTERNET_SETTINGS, "/v", valueName)
                .redirectErrorStream(true)
                .start();
        String foundValue = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.startsWith(valueName)) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+", 3);
                if (parts.length == 3) {
                    foundValue = parts[2].trim();
                }
            }
        }
        try {
            int exitCode = process.waitFor();
            return exitCode == 0 ? foundValue : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean parseRegistryBoolean(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            return Integer.decode(value).intValue() != 0;
        } catch (NumberFormatException e) {
            return "1".equals(value.trim());
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static class NoProxySelector extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    }

    private static class IEProxySelector extends ProxySelector {
        private final IEProxyConfig config;
        private final ProxySelector fallback;

        IEProxySelector(IEProxyConfig config, ProxySelector fallback) {
            this.config = config;
            this.fallback = fallback;
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) {
                throw new IllegalArgumentException("URI can't be null");
            }
            if (shouldBypass(uri)) {
                return List.of(Proxy.NO_PROXY);
            }
            Proxy proxy = getProxy(uri);
            if (proxy != null) {
                return List.of(proxy);
            }
            if (fallback != null) {
                return fallback.select(uri);
            }
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            if (fallback != null) {
                fallback.connectFailed(uri, sa, ioe);
            }
        }

        private Proxy getProxy(URI uri) {
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            ProxyEntry entry = parseProxyEntry(config.proxyServer, scheme);
            if (entry == null) {
                return null;
            }
            return new Proxy(entry.type, InetSocketAddress.createUnresolved(entry.host, entry.port));
        }

        private boolean shouldBypass(URI uri) {
            String host = uri.getHost();
            if (host == null || config.proxyOverride == null || config.proxyOverride.isEmpty()) {
                return false;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            for (String rawPattern : config.proxyOverride.split(";")) {
                String pattern = rawPattern.trim().toLowerCase(Locale.ROOT);
                if (pattern.isEmpty()) {
                    continue;
                }
                if ("<local>".equals(pattern) && !lowerHost.contains(".")) {
                    return true;
                }
                String regex = pattern.replace(".", "\\.").replace("*", ".*");
                if (lowerHost.matches(regex)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static ProxyEntry parseProxyEntry(String proxyServer, String scheme) {
        List<String> candidates = new ArrayList<>();
        if (proxyServer.contains("=")) {
            for (String part : proxyServer.split(";")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && scheme.equalsIgnoreCase(pair[0].trim())) {
                    candidates.add(pair[1].trim());
                }
                if (pair.length == 2 && candidates.isEmpty() && "socks".equalsIgnoreCase(pair[0].trim())) {
                    candidates.add("socks://" + pair[1].trim());
                }
            }
        } else {
            candidates.add(proxyServer.trim());
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return parseProxyAddress(candidates.get(0), scheme);
    }

    private static ProxyEntry parseProxyAddress(String address, String scheme) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        Proxy.Type type = address.toLowerCase(Locale.ROOT).startsWith("socks://") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        String normalized = address.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int separator = normalized.lastIndexOf(':');
        String host = separator >= 0 ? normalized.substring(0, separator) : normalized;
        int port = defaultPort(scheme);
        if (separator >= 0) {
            try {
                port = Integer.parseInt(normalized.substring(separator + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (host.isEmpty() || port <= 0 || port > 65535) {
            return null;
        }
        return new ProxyEntry(type, host, port);
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static class IEProxyConfig {
        boolean enabled;
        String proxyServer;
        String proxyOverride;
    }

    private static class ProxyEntry {
        final Proxy.Type type;
        final String host;
        final int port;

        ProxyEntry(Proxy.Type type, String host, int port) {
            this.type = type;
            this.host = host;
            this.port = port;
        }
    }
}
