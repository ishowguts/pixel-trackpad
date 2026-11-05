package local.pixeltrackpad;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;

/** The same strict, local-only invitation format for links and camera scans. */
final class Invitation {
    private static final Pattern FORMAT = Pattern.compile(
            "local[.]pixeltrackpad://pair/(?:v2/([0-9a-f:.]{1,39})/([0-9]{4,5})/|v1/)"
            + "([0-9a-f]{64})/([0-9a-f]{64})");
    final String host, port, fingerprint, token;

    private Invitation(String host, String port, String fingerprint, String token) {
        this.host = host; this.port = port; this.fingerprint = fingerprint; this.token = token;
    }

    static Invitation parse(String text) throws IOException {
        if (text == null || text.length() > 210) throw new IOException("Invalid invitation");
        var match = FORMAT.matcher(text);
        if (!match.matches()) throw new IOException("Invalid invitation");
        if (match.group(1) != null) endpoint(match.group(1), match.group(2), false);
        return new Invitation(match.group(1), match.group(2), match.group(3), match.group(4));
    }

    static InetSocketAddress endpoint(String host, String portText, boolean legacy) throws IOException {
        boolean ipv4 = host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        if (!ipv4 && !(host.contains(":") && host.matches("[0-9a-fA-F:]+"))) throw new IOException("Use IP literal");
        if (ipv4) for (String part : host.split("\\.")) if (Integer.parseInt(part) > 255) throw new IOException("Invalid IP literal");
        InetAddress address = InetAddress.getByName(host);
        byte[] bytes = address.getAddress();
        if (!address.isSiteLocalAddress() && !(bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                && !(legacy && address.isLoopbackAddress())) throw new IOException("Use private LAN IP");
        if (!legacy && bytes.length == 16 && (bytes[0] & 0xfe) != 0xfc) throw new IOException("Use IPv6 ULA");
        if (!portText.matches("[0-9]{4,5}")) throw new IOException("Invalid port");
        int port = Integer.parseInt(portText);
        if (port < 1024 || port > 65535) throw new IOException("Invalid port");
        return new InetSocketAddress(address, port);
    }
}
