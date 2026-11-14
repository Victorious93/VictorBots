import com.victorious.bots.*;
import java.util.*;

/** In-memory test doubles that obey the same contracts as the Android implementations. */
public final class Fakes {
  public static final class MemKV implements KV {
    public final Map<String, String> m = new HashMap<>();
    public String get(String k, String d) { return m.containsKey(k) ? m.get(k) : d; }
    public void put(String k, String v) { m.put(k, v); }
  }

  /** Same binding rule as SecretStore: a key only reads back for the origin it was saved for. */
  public static final class MemSecrets implements Secrets {
    final Map<Long, String[]> m = new HashMap<>();
    public String read(long id, String origin) {
      String[] v = m.get(id);
      if (v == null) return "";
      if (!v[0].equals(origin)) throw new SecurityException("bound to another origin");
      return v[1];
    }
    public void save(long id, String origin, String key) { m.put(id, new String[] {origin, key}); }
    public void remove(long id) { m.remove(id); }
    public boolean has(long id) { return m.containsKey(id); }
  }

  public static final class Env {
    public JdbcDb db;
    public MemKV kv = new MemKV();
    public MemSecrets secrets = new MemSecrets();
    public Profiles profiles;
    public Gateway gateway;
    public List<String> calls = new ArrayList<>();      // "baseurl|key|model"
    public Gateway.Transport transport = (cfg, turns, stream, sink, cancel) -> {
      calls.add(cfg.base + "|" + cfg.key + "|" + cfg.model);
      Provider.Reply r = new Provider.Reply();
      r.text = "ok";
      r.usage.in = 20L; r.usage.out = 5L; r.modelReported = cfg.model + "-rev";
      r.latencyMs = 12;
      return r;
    };
    public long cloud, pc, dca, bot;
  }

  public static Env env() throws Exception {
    Env e = new Env();
    e.db = new JdbcDb("jdbc:sqlite::memory:");
    e.db.exec("PRAGMA foreign_keys=OFF");
    e.db.tx(() -> { Migrations.upgrade(e.db, 0); return null; });
    e.db.exec("PRAGMA foreign_keys=ON");
    e.profiles = new Profiles(e.db, e.kv, e.secrets);
    e.gateway = new Gateway(e.db, e.profiles, e.secrets, (cfg, t, s, k, c) -> e.transport.chat(cfg, t, s, k, c));
    e.dca = e.db.insert("INSERT INTO profiles(name,kind,created) VALUES('DroidCommand AI','DROIDCOMMAND',1)");
    e.cloud = e.profiles.save(0, "OpenAI-style", Policy.Kind.CLOUD, "https://api.example.com/v1", "cloud-model", "", null);
    e.secrets.save(e.cloud, "https://api.example.com:443", "cloud-key-1234");
    e.pc = e.profiles.save(0, "My PC", Policy.Kind.PC, "https://192.168.1.20:8443/v1", "pc-model", "", null);
    e.secrets.save(e.pc, "https://192.168.1.20:8443", "pc-token-5678");
    e.bot = e.db.insert("INSERT INTO bots(name,description,instructions,avatar,uid) VALUES('B','d','i','x','b-uid')");
    return e;
  }

  private Fakes() {}
}
