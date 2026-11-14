package com.victorious.bots;

import java.net.URI;
import java.util.Locale;

/** HTTPS endpoint parsing, origin binding and host hints. Pure Java. */
public final class Hosts {
  /** Normalises an OpenAI-compatible base URL: https only, no credentials/query/fragment. */
  public static String base(String raw) {
    if (raw == null) throw new IllegalArgumentException("Enter an HTTPS URL.");
    String s = raw.trim();
    try {
      URI uri = new URI(s);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || uri.getQuery() != null)
        throw new IllegalArgumentException(
            "Use an HTTPS URL without credentials, query, or fragment. Plain http:// is never"
                + " accepted.");
      int port = uri.getPort();
      if (port == 0 || port > 65535) throw new IllegalArgumentException("Invalid port.");
      String path = uri.getPath() == null ? "" : uri.getPath();
      for (String suffix : new String[] {"/chat/completions", "/chat/completions/"})
        if (path.endsWith(suffix)) path = path.substring(0, path.length() - suffix.length());
      while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
      if (path.contains("..") || path.contains("//"))
        throw new IllegalArgumentException("Invalid URL path.");
      return "https://" + hostForUri(uri) + (port > 0 ? ":" + port : "") + path;
    } catch (java.net.URISyntaxException e) {
      throw new IllegalArgumentException("Invalid URL.");
    }
  }

  private static String hostForUri(URI uri) {
    String h = uri.getHost().toLowerCase(Locale.ROOT);
    return h.indexOf(':') >= 0 && !h.startsWith("[") ? "[" + h + "]" : h;
  }

  /** scheme://host:port with the default port made explicit, used to bind secrets to an endpoint. */
  public static String origin(String url) {
    try {
      URI u = new URI(url);
      int port = u.getPort() < 0 ? 443 : u.getPort();
      return "https://" + hostForUri(u) + ":" + port;
    } catch (java.net.URISyntaxException e) {
      throw new IllegalArgumentException("Invalid URL.");
    }
  }

  public static String host(String url) {
    try {
      return new URI(url).getHost();
    } catch (java.net.URISyntaxException e) {
      return "?";
    }
  }

  public static String chatUrl(String base) {
    return base + "/chat/completions";
  }

  public static String modelsUrl(String base) {
    return base + "/models";
  }

  /** The PC runner lives beside the OpenAI-compatible API on the same authenticated origin. */
  public static String runnerBase(String base) {
    return origin(base) + "/runner/v1";
  }

  /** Ollama-native API (capabilities / context length) on the same origin; only used when reachable. */
  public static String apiBase(String base) {
    return origin(base) + "/api";
  }

  public static boolean isLoopback(String host) {
    if (host == null) return false;
    String h = host.toLowerCase(Locale.ROOT);
    return h.equals("localhost")
        || h.equals("::1")
        || h.equals("[::1]")
        || h.startsWith("127.")
        || h.equals("0.0.0.0");
  }

  /** RFC1918, CGNAT/Tailscale, link-local or a .local/.ts.net style name. Informational only. */
  public static boolean looksPrivate(String host) {
    if (host == null) return false;
    String h = host.toLowerCase(Locale.ROOT);
    if (h.endsWith(".local") || h.endsWith(".ts.net") || h.endsWith(".lan") || h.endsWith(".home.arpa"))
      return true;
    String[] p = h.split("\\.");
    if (p.length == 4) {
      try {
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
        return a == 10
            || (a == 172 && b >= 16 && b <= 31)
            || (a == 192 && b == 168)
            || (a == 100 && b >= 64 && b <= 127)
            || (a == 169 && b == 254);
      } catch (NumberFormatException e) {
        return false;
      }
    }
    return false;
  }

  /** Human warning shown when saving a PC endpoint; empty when nothing notable. */
  public static String pcHint(String base) {
    String host = host(base);
    if (isLoopback(host))
      return "“" + host + "” on a phone means this phone, not your PC. Use your PC's LAN or VPN"
          + " name/address.";
    if (!looksPrivate(host))
      return "This host does not look like a private LAN/VPN address. A PC-hosted provider should"
          + " not be reachable from the open internet unless it is protected by TLS and a token.";
    return "";
  }

  private Hosts() {}
}
