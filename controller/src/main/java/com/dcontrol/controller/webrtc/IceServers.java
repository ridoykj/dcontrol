package com.dcontrol.controller.webrtc;

import com.dcontrol.controller.common.config.AppProperties;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Builds the ICE server list for one socket from {@code dcontrol.webrtc.ice-servers}: {@code {host}} becomes
 * the host that socket connected to, so the browser and the emulator reach the same coturn under different
 * names. The host comes from the client's {@code Host} header, so it is only used when it is a plain host
 * name or IP literal; otherwise the host of {@code public-base-url} is used.
 */
@Component
public class IceServers {

    /** The {@code RTCIceServer} shape sent to browsers and phones. */
    public record IceServer(List<String> urls, String username, String credential) {
    }

    private static final String HOST_PLACEHOLDER = "{host}";
    private static final Pattern HOST_NAME = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final List<AppProperties.IceServer> configured;
    private final TurnCredentials credentials;
    private final String fallbackHost;

    public IceServers(AppProperties properties, TurnCredentials credentials) {
        this.configured = properties.webrtc().iceServers();
        this.credentials = credentials;
        this.fallbackHost = URI.create(properties.publicBaseUrl()).getHost();
    }

    /** The servers for a socket that connected to {@code host}, with TURN credentials for {@code userKey}. */
    public List<IceServer> forSocket(String host, UUID userKey) {
        String urlHost = urlHost(host, fallbackHost);
        TurnCredentials.Credentials turn = configured.stream().anyMatch(AppProperties.IceServer::turn)
                ? credentials.issue(userKey)
                : null;
        return configured.stream()
                .map(server -> new IceServer(
                        server.urls().stream().map(url -> url.replace(HOST_PLACEHOLDER, urlHost)).toList(),
                        server.turn() ? turn.username() : null,
                        server.turn() ? turn.credential() : null))
                .toList();
    }

    /** The host as it may appear in a {@code stun:}/{@code turn:} URL; IPv6 literals get brackets. */
    static String urlHost(String host, String fallbackHost) {
        if (host != null) {
            String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            if (bare.contains(":")) {
                if (IPV6.matcher(bare).matches()) {
                    return "[" + bare + "]";
                }
            } else if (HOST_NAME.matcher(bare).matches()) {
                return bare;
            }
        }
        return fallbackHost;
    }
}
