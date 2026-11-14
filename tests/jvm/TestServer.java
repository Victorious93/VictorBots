import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.*;

/** A real TLS server (self-signed certificate made with the JDK's keytool) for provider/runner client tests. */
public final class TestServer {
  public static final class Seen {
    public String method, path, auth, body;
  }

  public final HttpsServer server;
  public final int port;
  public final String fingerprint;
  public final List<Seen> seen = new CopyOnWriteArrayList<>();
  private final Map<String, HttpHandler> routes = new java.util.concurrent.ConcurrentHashMap<>();

  public static String[] makeCert(Path dir, String ip) throws Exception {
    Path ks = dir.resolve("ks.p12");
    Files.deleteIfExists(ks);
    Process p = new ProcessBuilder("keytool", "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30", "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:" + ip).redirectErrorStream(true).start();
    p.getInputStream().readAllBytes();
    if (p.waitFor() != 0) throw new IllegalStateException("keytool failed");
    return new String[] {ks.toString()};
  }

  public TestServer(Path dir) throws Exception {
    String ks = makeCert(dir, "127.0.0.1")[0];
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(Paths.get(ks))) {
      store.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(store, "changeit".toCharArray());
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), null, null);
    fingerprint = com.victorious.bots.Tls.sha256Hex(store.getCertificate("t").getEncoded());
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(ctx));
    server.createContext("/", ex -> {
      Seen s = new Seen();
      s.method = ex.getRequestMethod();
      s.path = ex.getRequestURI().getPath();
      s.auth = ex.getRequestHeaders().getFirst("Authorization");
      s.body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      seen.add(s);
      HttpHandler h = routes.get(s.path);
      if (h == null) {
        reply(ex, 404, "{\"error\":\"not found\"}");
        return;
      }
      h.handle(ex);
    });
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
    port = server.getAddress().getPort();
  }

  public String base(String path) {
    return "https://127.0.0.1:" + port + path;
  }

  public void on(String path, HttpHandler h) {
    routes.put(path, h);
  }

  public void stop() {
    server.stop(0);
  }

  public static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    try (OutputStream o = ex.getResponseBody()) {
      o.write(b);
    }
  }

  public static void sse(HttpExchange ex, List<String> chunks, long delayMs) throws IOException {
    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, 0);
    try (OutputStream o = ex.getResponseBody()) {
      for (String c : chunks) {
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException e) {
          return;
        }
        o.write(("data: " + c + "\n\n").getBytes(StandardCharsets.UTF_8));
        o.flush();
      }
    }
  }
}
