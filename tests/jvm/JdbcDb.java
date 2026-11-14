import com.victorious.bots.Db;
import java.sql.*;
import java.util.*;
import org.json.JSONObject;

/** Real-SQLite adapter for JVM tests (sqlite-jdbc). Mirrors the Android adapter's semantics. */
public final class JdbcDb implements Db {
  public final Connection c;
  private int depth;

  public JdbcDb(String url) throws SQLException {
    c = DriverManager.getConnection(url);
    c.setAutoCommit(true);
  }

  private static void bind(PreparedStatement ps, Object[] a) throws SQLException {
    for (int i = 0; i < a.length; i++) {
      Object v = a[i];
      if (v == null) ps.setNull(i + 1, Types.NULL);
      else if (v instanceof Long) ps.setLong(i + 1, (Long) v);
      else if (v instanceof Integer) ps.setLong(i + 1, (Integer) v);
      else if (v instanceof Boolean) ps.setLong(i + 1, ((Boolean) v) ? 1 : 0);
      else ps.setString(i + 1, v.toString());
    }
  }

  @Override
  public List<JSONObject> rows(String sql, Object... args) {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        List<JSONObject> out = new ArrayList<>();
        ResultSetMetaData m = rs.getMetaData();
        while (rs.next()) {
          JSONObject o = new JSONObject();
          for (int i = 1; i <= m.getColumnCount(); i++) {
            String v = rs.getString(i);
            if (v != null) o.put(m.getColumnLabel(i), v);
          }
          out.add(o);
        }
        return out;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage() + " :: " + sql, e);
    }
  }

  @Override
  public long insert(String sql, Object... args) {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      ps.executeUpdate();
      try (Statement s = c.createStatement();
          ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage() + " :: " + sql, e);
    }
  }

  @Override
  public int update(String sql, Object... args) {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      bind(ps, args);
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage() + " :: " + sql, e);
    }
  }

  @Override
  public void exec(String sql) {
    try (Statement s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage() + " :: " + sql, e);
    }
  }

  @Override
  public <T> T tx(Work<T> w) {
    try {
      if (depth == 0) c.setAutoCommit(false);
      depth++;
      T r;
      try {
        r = w.run();
      } catch (Exception e) {
        depth--;
        if (depth == 0) {
          c.rollback();
          c.setAutoCommit(true);
        }
        if (e instanceof RuntimeException) throw (RuntimeException) e;
        throw new IllegalStateException(e);
      }
      depth--;
      if (depth == 0) {
        c.commit();
        c.setAutoCommit(true);
      }
      return r;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
