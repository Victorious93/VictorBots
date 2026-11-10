package com.victorious.bots;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

public final class Provider {
  public static final class Reply {
    public final String text, usage;

    Reply(String t, String u) {
      text = t;
      usage = u;
    }
  }

  public static Reply ask(String endpoint, String key, String model, List<Rules.Turn> turns)
      throws Exception {
    Rules.length(model, 160, "Model");
    if (key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0)
      throw new IllegalArgumentException("Invalid API key.");
    HttpsURLConnection c = (HttpsURLConnection) new URL(Rules.endpoint(endpoint)).openConnection();
    try {
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(15000);
      c.setReadTimeout(90000);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
      JSONArray messages = new JSONArray();
      for (Rules.Turn t : turns)
        messages.put(new JSONObject().put("role", t.role).put("content", t.content));
      byte[] body =
          new JSONObject()
              .put("model", model)
              .put("messages", messages)
              .put("stream", false)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      c.setFixedLengthStreamingMode(body.length);
      try (OutputStream o = c.getOutputStream()) {
        o.write(body);
      }
      int status = c.getResponseCode();
      if (status < 200 || status >= 300)
        throw new IOException(
            "Provider returned HTTP "
                + status
                + ". Check your endpoint, model, key, and account limits. Nothing was retried.");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = c.getInputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          if (out.size() + n > 1048576) throw new IOException("Provider response exceeds 1 MB.");
          out.write(b, 0, n);
        }
      }
      JSONObject root = new JSONObject(out.toString("UTF-8"));
      JSONObject msg = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
      Object content = msg.opt("content");
      if (!(content instanceof String) || ((String) content).trim().isEmpty())
        throw new IOException("Provider returned no text. Tool-only responses are not executed.");
      String usage = "Provider did not report token usage";
      JSONObject u = root.optJSONObject("usage");
      if (u != null && u.has("total_tokens"))
        usage = u.optLong("total_tokens") + " tokens reported by provider";
      return new Reply((String) content, usage);
    } finally {
      c.disconnect();
    }
  }
}
