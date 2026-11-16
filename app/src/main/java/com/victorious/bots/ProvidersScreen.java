package com.victorious.bots;

import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.*;
import org.json.JSONException;
import org.json.JSONObject;

/** Provider profiles, owner inference policy, connection diagnostics, usage ledger. */
final class ProvidersScreen {
  private final Host h;
  private final Ui ui;
  private final BotApp app;
  /** Last diagnostics per profile (in memory only). */
  static final Map<Long, Diagnostics.Report> reports = new HashMap<>();
  private static final Set<Long> running = Collections.synchronizedSet(new HashSet<Long>());

  ProvidersScreen(Host h) {
    this.h = h;
    this.ui = h.ui();
    this.app = h.app();
  }

  void render(LinearLayout body) {
    body.addView(ui.text("PROVIDERS & POLICY", 11, Ui.ACCENT));
    body.addView(ui.heading("Where text may go.", 30));
    body.addView(ui.text("A profile is one connection. Each bot can use its own profile and model, or inherit the default. Your policy below is checked before every request of every kind: chat, routing, memory, summaries, routines and tests.", 14, Ui.MUTED));
    policyCard(body);
    for (Profile p : app.profiles.all()) profileCard(body, p);
    body.addView(ui.button("＋  Add a cloud provider (HTTPS API)", () -> edit(null, Policy.Kind.CLOUD), true));
    body.addView(ui.button("＋  Add my PC (authenticated HTTPS)", () -> edit(null, Policy.Kind.PC), true));
    body.addView(ui.neonButton("＋  Add VictorLinux PC (optional preset)", () -> editVictorLinux(null)));
    body.addView(ui.button("Usage & requests log", () -> h.go("More", "usage", 0), false));
    LinearLayout help = ui.card(body);
    help.addView(ui.text("Connecting your PC", 16, Ui.FG));
    help.addView(ui.text("“localhost” or 127.0.0.1 on a phone means this phone, not your PC. Use your PC's LAN, VPN or Tailscale address. The PC must serve HTTPS with a bearer token: see MANUAL.md (the VictorBots PC runner does this for Ollama or any OpenAI-compatible server). For a private or self-signed certificate, paste its SHA-256 fingerprint in the profile; only that exact certificate is trusted, for that profile only.", 13, Ui.MUTED));
  }

  private void policyCard(LinearLayout body) {
    LinearLayout c = ui.card(body);
    Policy.Mode cur = app.profiles.globalMode();
    c.addView(ui.heading("Inference policy: " + cur.label, 18));
    c.addView(ui.text(cur.explanation, 13, Ui.MUTED));
    c.addView(ui.button("Change policy", () -> {
      Policy.Mode[] all = Policy.Mode.values();
      String[] labels = new String[all.length];
      for (int i = 0; i < all.length; i++) labels[i] = all[i].label + (all[i].usable ? "" : "  (unavailable)");
      ui.choose("Inference policy", labels, w -> {
        try {
          app.profiles.setGlobalMode(all[w]);
          h.refresh();
        } catch (IllegalArgumentException e) {
          ui.info("Not available", e.getMessage());
        }
      });
    }, false));
    List<Profile> all = app.profiles.all();
    Profile def = app.profiles.defaultProfile();
    c.addView(ui.text("Default for bots that inherit: " + (def == null ? "none" : def.name), 13, Ui.FG));
    c.addView(ui.button("Choose default profile", () -> {
      String[] n = new String[all.size()];
      for (int i = 0; i < n.length; i++) n[i] = all.get(i).describe();
      ui.choose("Default profile", n, w -> { app.profiles.setDefault(all.get(w).id); h.refresh(); });
    }, false));
    Profile fb = app.profiles.get(app.kv.num(Profiles.FALLBACK_KEY, 0));
    c.addView(ui.text("Cloud choice offered after a PC failure (PC-first policy only, per request, never automatic): " + (fb == null ? "none" : fb.name), 12, Ui.MUTED));
    c.addView(ui.button("Choose that cloud profile", () -> {
      List<Profile> clouds = new ArrayList<>();
      for (Profile p : all) if (p.kind == Policy.Kind.CLOUD) clouds.add(p);
      String[] n = new String[clouds.size() + 1];
      n[0] = "None";
      for (int i = 0; i < clouds.size(); i++) n[i + 1] = clouds.get(i).name;
      ui.choose("Fallback offer", n, w -> { app.kv.put(Profiles.FALLBACK_KEY, w == 0 ? "0" : "" + clouds.get(w - 1).id); h.refresh(); });
    }, false));
  }

  private void profileCard(LinearLayout body, Profile p) {
    LinearLayout c = ui.card(body);
    c.addView(ui.heading(p.name, 19));
    c.addView(ui.text(Diagnostics.destination(p) + (p.model.isEmpty() ? "" : "\nModel: " + p.model), 13, Ui.MUTED));
    if (p.kind != Policy.Kind.DROIDCOMMAND) {
      boolean hasKey = app.secrets.has(p.id);
      boolean keyOk = hasKey && (p.kind != Policy.Kind.PC || app.secrets.usable(p.id, p.binding()));
      String keyLine = !hasKey ? "none stored" : keyOk ? "stored encrypted, bound to " + Hosts.host(p.base) + (p.kind == Policy.Kind.PC && !p.pin.isEmpty() ? " and its certificate pin" : "") : "stored, but it no longer matches this profile's address or certificate pin (or uses an older format). Edit the profile and enter it again.";
      c.addView(ui.text("Key: " + keyLine + (p.pin.isEmpty() ? "" : "\nPinned certificate: " + p.pin.substring(0, 12) + "…"), 12, keyOk || p.kind == Policy.Kind.CLOUD ? Ui.MUTED : Ui.WARN));
      if (p.isVictorLinux()) {
        Profile.Runner rn = p.runner();
        c.addView(ui.text(rn == null ? "Runner: not configured. Jobs, routines and connectors stay unavailable for this profile." : "Runner: " + Hosts.host(rn.base) + ":" + VictorLinux.portOf(rn.base) + (rn.separate ? " (separate address: its own token and pin)" : " (same address as this PC: shares its token and pin)"), 12, rn == null ? Ui.NEON_MAGENTA : Ui.NEON_CYAN));
      }
      String hint = p.kind == Policy.Kind.PC ? Hosts.pcHint(p.base) : "";
      if (!hint.isEmpty()) c.addView(ui.text("⚠ " + hint, 12, Ui.WARN));
    }
    Diagnostics.Report r = reports.get(p.id);
    if (r == null) r = app.diagnostics.passive(p);
    c.addView(ui.text("CONNECTION STATE", 10, Ui.ACCENT));
    for (Diagnostics.Step s : r.steps()) {
      int col = s.state == Diagnostics.State.OK ? Ui.ACCENT : s.state == Diagnostics.State.FAILED ? Ui.BAD : s.state == Diagnostics.State.BLOCKED ? Ui.WARN : Ui.MUTED;
      String mark = s.state == Diagnostics.State.OK ? "✓ " : s.state == Diagnostics.State.FAILED ? "✗ " : s.state == Diagnostics.State.BLOCKED ? "⛔ " : "• ";
      c.addView(ui.text(mark + s.name + ": " + s.detail, 12, col));
    }
    if (!r.usageLine().isEmpty()) c.addView(ui.text(r.usageLine() + (r.reportedModel.isEmpty() ? "" : "\nRequested model: " + r.requestedModel + " · server reported: " + r.reportedModel), 12, Ui.FG));
    if (!r.note.isEmpty()) c.addView(ui.text(r.note, 11, Ui.MUTED));
    if (p.kind != Policy.Kind.DROIDCOMMAND) {
      boolean busy = running.contains(p.id);
      c.addView(ui.button(busy ? "Testing…" : "Check connection (no chat content sent)", () -> run(p, false), false));
      c.addView(ui.button("Live test: send one short message…", () -> {
        Policy.Mode mode = app.profiles.globalMode();
        ui.confirm("Send a test message?", Diagnostics.liveTestWarning(p, mode), "Send test", () -> run(p, true));
      }, false));
      c.addView(ui.button("Capabilities (configured / advertised / tested)", () -> {
        StringBuilder b = new StringBuilder();
        for (CapabilityReport.Row row : CapabilityReport.build(app.profiles.get(p.id))) b.append(row.line()).append("\n\n");
        ui.info("Capabilities of " + p.name, b.toString());
      }, false));
    }
    c.addView(ui.button(p.kind == Policy.Kind.DROIDCOMMAND ? "About this profile" : "Edit", () -> {
      if (p.kind == Policy.Kind.DROIDCOMMAND) ui.info("DroidCommand AI", "Built in. Chat goes through DroidCommand's text interface after you tap Allow there. DroidCommand decides the model and where text goes; VictorBots cannot verify that, so this profile is refused under PC-only policies and for any unattended work (team rounds, routines, memory, routing).");
      else if (p.isVictorLinux()) editVictorLinux(p);
      else edit(p, p.kind);
    }, false));
  }

  private void run(Profile p, boolean live) {
    if (!running.add(p.id)) return;
    h.refresh();
    app.runAsync(() -> {
      try {
        Profile fresh = app.profiles.get(p.id);
        reports.put(p.id, live ? app.diagnostics.liveTest(fresh, fresh.streaming()) : app.diagnostics.probe(fresh));
      } finally {
        running.remove(p.id);
      }
    });
  }

  private void edit(Profile existing, Policy.Kind kind) {
    LinearLayout f = ui.column();
    f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    boolean pc = kind == Policy.Kind.PC;
    EditText name = ui.field(f, "Profile name", existing == null ? (pc ? "My PC" : "") : existing.name, false);
    EditText base = ui.field(f, pc ? "PC address, e.g. https://192.168.1.20:8443/v1" : "API base URL, e.g. https://your-provider/v1", existing == null ? "" : existing.base, false);
    base.setHint("https://…");
    EditText model = ui.field(f, "Model id (type it, or use Discover)", existing == null ? "" : existing.model, false);
    f.addView(ui.button("Discover models from this server", () -> discover(existing, base, model), false));
    EditText key = ui.field(f, pc ? "Access token (required for a PC; blank keeps the stored one)" : "API key (blank keeps the stored one)", "", false);
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    EditText pin = null;
    if (pc) pin = ui.field(f, "Certificate SHA-256 fingerprint (only for a private/self-signed certificate; blank uses normal trust)", existing == null ? "" : existing.pin, true);
    final EditText pinF = pin;
    JSONObject caps = existing == null ? new JSONObject() : existing.caps;
    f.addView(ui.text("What this model supports (you decide; Test shows what the server actually did)", 12, Ui.ACCENT));
    Switch streaming = ui.toggle(f, "Stream replies (shows first-token time)", caps.optBoolean("streaming", existing == null));
    Switch tools = ui.toggle(f, "Model supports tool calls (VictorBots still never executes them itself)", caps.optBoolean("tools", false));
    Switch images = ui.toggle(f, "Model accepts images", caps.optBoolean("images", false));
    EditText ctx = ui.field(f, "Context window in tokens (blank = assume 4096)", caps.optInt("context_tokens", 0) == 0 ? "" : "" + caps.optInt("context_tokens"), false);
    ctx.setInputType(InputType.TYPE_CLASS_NUMBER);
    EditText reasoning = ui.field(f, "Reasoning effort to send (blank = not sent)", caps.optString("reasoning_effort", ""), false);
    String[] billing = {"metered", "subscription", "self_hosted"};
    f.addView(ui.text("How you are billed (for labelling estimates only)", 12, Ui.ACCENT));
    Spinner bs = new Spinner(h.act());
    bs.setAdapter(new ArrayAdapter<>(h.act(), android.R.layout.simple_spinner_dropdown_item, new String[] {"Per token (metered)", "Flat subscription", "My own hardware"}));
    String curBilling = caps.optString("billing", pc ? "self_hosted" : "metered");
    for (int i = 0; i < billing.length; i++) if (billing[i].equals(curBilling)) bs.setSelection(i);
    f.addView(bs);
    EditText pin1 = ui.field(f, "Price per 1M input tokens (optional, for an estimate)", caps.has("price_in_per_m") ? "" + caps.optDouble("price_in_per_m") : "", false);
    EditText pout = ui.field(f, "Price per 1M output tokens (optional)", caps.has("price_out_per_m") ? "" + caps.optDouble("price_out_per_m") : "", false);
    ui.form(existing == null ? "Add a provider" : "Edit " + existing.name, f, "Save", () -> {
      JSONObject nc = new JSONObject();
      try {
        nc.put("streaming", streaming.isChecked()).put("tools", tools.isChecked()).put("images", images.isChecked());
        String c = ctx.getText().toString().trim();
        if (!c.isEmpty()) {
          int v = Integer.parseInt(c);
          if (v < 512 || v > 4_000_000) throw new IllegalArgumentException("Context window must be between 512 and 4,000,000 tokens.");
          nc.put("context_tokens", v);
        }
        String re = reasoning.getText().toString().trim();
        if (!re.matches("[a-z]{0,12}")) throw new IllegalArgumentException("Reasoning effort is a short lowercase word.");
        if (!re.isEmpty()) nc.put("reasoning_effort", re);
        nc.put("billing", billing[bs.getSelectedItemPosition()]);
        if (!pin1.getText().toString().trim().isEmpty()) nc.put("price_in_per_m", Double.parseDouble(pin1.getText().toString().trim()));
        if (!pout.getText().toString().trim().isEmpty()) nc.put("price_out_per_m", Double.parseDouble(pout.getText().toString().trim()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("A number field has a value that is not a number.");
      }
      String newBase = Hosts.base(base.getText().toString());
      String token = key.getText().toString();
      boolean originChanged = existing != null && !existing.base.isEmpty() && !Hosts.origin(existing.base).equals(Hosts.origin(newBase));
      String pinText = pinF == null ? "" : pinF.getText().toString().trim();
      String newBinding = Profile.binding(kind, newBase, pinText.isEmpty() ? "" : Rules.pin(pinText));
      if (pc && token.isEmpty() && existing != null && app.secrets.has(existing.id) && !app.secrets.usable(existing.id, newBinding))
        throw new IllegalArgumentException("The address or certificate pin changed (or the stored token uses an older format), so the stored token is not reused. Enter the access token again.");
      if (pc && token.isEmpty() && (existing == null || originChanged || !app.secrets.has(existing.id)))
        throw new IllegalArgumentException("A PC profile needs its access token. VictorBots will not call a PC endpoint anonymously.");
      if (originChanged && token.isEmpty() && existing.kind == Policy.Kind.CLOUD && app.secrets.has(existing.id)) {
        // The old key is dropped by Profiles.save; say so explicitly rather than silently reusing it.
        ui.toast("The endpoint changed, so the old key was removed. Enter this endpoint's key to use one.");
      }
      long id = app.profiles.save(existing == null ? 0 : existing.id, name.getText().toString(), kind, newBase, model.getText().toString(), pinF == null ? "" : pinF.getText().toString(), nc);
      if (!token.isEmpty()) {
        try {
          app.secrets.save(id, newBinding, token);
        } catch (Exception e) {
          throw new IllegalStateException("The key could not be stored securely: " + e.getMessage());
        }
      }
      if (app.profiles.defaultProfile() == null || app.profiles.all().size() == 2) app.profiles.setDefault(id);
      reports.remove(id);
      h.refresh();
      return true;
    }, null);
    if (existing != null)
      f.addView(ui.button("Remove stored key", () -> ui.confirm("Remove key?", "The key for " + existing.name + " will be deleted from this device.", "Remove", () -> { app.secrets.remove(existing.id); h.refresh(); }), false));
    if (existing != null)
      f.addView(ui.button("Delete this profile", () -> ui.confirm("Delete profile?", "Bots using it go back to the default. Its key is deleted.", "Delete", () -> { app.profiles.delete(existing.id); h.refresh(); }), false));
  }

  /** The optional VictorLinux preset. Never used unless the owner taps "Add VictorLinux PC". */
  private void editVictorLinux(Profile existing) {
    LinearLayout f = ui.column();
    f.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
    f.setBackgroundColor(Ui.NEON_BG);
    f.addView(ui.text("VICTORLINUX PC · OPTIONAL PRESET", 11, Ui.NEON_CYAN));
    f.addView(ui.text("HTTPS only. The access token and the certificate SHA-256 fingerprint are both required, redirects are refused, and only the exact certificate you pin is trusted for this profile.", 12, Ui.MUTED));
    JSONObject old = existing == null ? new JSONObject() : existing.caps;
    EditText name = ui.field(f, "Profile name", existing == null ? "VictorLinux" : existing.name, false);
    EditText host = ui.field(f, "PC host name or IP address (no https://, no port)", existing == null ? "" : Hosts.host(existing.base), false);
    EditText port = ui.field(f, "Port (blank = " + VictorLinux.DEFAULT_PORT + ")", existing == null ? "" : "" + VictorLinux.portOf(existing.base), false);
    port.setInputType(InputType.TYPE_CLASS_NUMBER);
    EditText model = ui.field(f, "Model id (default " + VictorLinux.DEFAULT_MODEL + ", editable)", existing == null ? VictorLinux.DEFAULT_MODEL : existing.model, false);
    EditText token = ui.field(f, "Access token (required; blank keeps the stored one only if address and pin are unchanged)", "", false);
    token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    EditText pin = ui.field(f, "Certificate SHA-256 fingerprint (required)", existing == null ? "" : existing.pin, true);
    f.addView(ui.text("Chat goes to https://<host>:<port>/v1/chat/completions.", 11, Ui.MUTED));

    TextView note = ui.text("Capability detection has not been run.", 12, Ui.MUTED);
    VictorLinux.Capabilities[] found = {null};
    String[] foundFor = {""};
    Switch streaming = ui.toggle(f, "Stream replies (shows first-token time)", old.optBoolean("streaming", true));
    Switch tools = ui.toggle(f, "Model supports tool calls (VictorBots still never executes them itself)", old.optBoolean("tools", false));
    Switch images = ui.toggle(f, "Model accepts images", old.optBoolean("images", false));
    f.addView(ui.neonButton("Detect capabilities (GET " + VictorLinux.CAPABILITIES_PATH + ")", () -> {
      Provider.Cfg cfg;
      try {
        String typed = token.getText().toString();
        String tok = typed.isEmpty() ? storedToken(existing, host, port, pin) : typed;
        cfg = VictorLinux.discoveryCfg(host.getText().toString(), port.getText().toString(), pin.getText().toString(), tok);
      } catch (IllegalArgumentException e) {
        note.setTextColor(Ui.WARN);
        note.setText("Cannot detect yet: " + e.getMessage());
        return;
      }
      note.setTextColor(Ui.MUTED);
      note.setText("Asking the PC… (sends only your token; no chat text; nothing is run)");
      app.runAsync(() -> {
        VictorLinux.Capabilities c = VictorLinux.fetch(cfg);
        h.act().runOnUiThread(() -> {
          found[0] = c;
          foundFor[0] = cfg.base + "|" + cfg.pin;
          note.setTextColor(c.ok ? Ui.NEON_CYAN : Ui.WARN);
          note.setText(c.describe());
          if (c.ok && Boolean.TRUE.equals(c.toolCalls)) tools.setChecked(true);
        });
      });
    }));
    f.addView(note);
    EditText ctx = ui.field(f, "Context window in tokens (blank = assume 4096)", old.optInt("context_tokens", 0) == 0 ? "" : "" + old.optInt("context_tokens"), false);
    ctx.setInputType(InputType.TYPE_CLASS_NUMBER);

    f.addView(ui.text("RUNNER (OPTIONAL)", 11, Ui.NEON_CYAN));
    f.addView(ui.text("Only if you run the VictorBots PC runner. Leave empty and jobs, routines and connectors show “" + VictorLinux.NO_RUNNER + "”. A runner on a different address never receives the PC's token: it needs its own.", 12, Ui.MUTED));
    EditText runnerUrl = ui.field(f, "Runner URL (e.g. https://host:8443/runner/v1)", old.optString("runner_url"), false);
    EditText runnerToken = ui.field(f, "Runner access token (only for a different address; blank keeps the stored one)", "", false);
    runnerToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    runnerToken.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    EditText runnerPin = ui.field(f, "Runner certificate SHA-256 (only for a different address)", old.optString("runner_pin"), true);

    TextView err = ui.text("", 13, Ui.NEON_MAGENTA);
    err.setVisibility(View.GONE);
    f.addView(err);
    ui.form(existing == null ? "Add VictorLinux PC" : "Edit " + existing.name, f, "Save", () -> {
      err.setVisibility(View.GONE);
      try {
        JSONObject nc = new JSONObject();
        for (java.util.Iterator<String> it = old.keys(); it.hasNext(); ) {
          String k = it.next();
          nc.put(k, old.get(k));
        }
        nc.put("streaming", streaming.isChecked()).put("tools", tools.isChecked()).put("images", images.isChecked());
        nc.remove("context_tokens");
        String c = ctx.getText().toString().trim();
        if (!c.isEmpty()) {
          int v;
          try {
            v = Integer.parseInt(c);
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Context window must be a number.");
          }
          if (v < 512 || v > 4_000_000) throw new IllegalArgumentException("Context window must be between 512 and 4,000,000 tokens.");
          nc.put("context_tokens", v);
        }
        long id = app.profiles.saveVictorLinux(existing == null ? 0 : existing.id, name.getText().toString(), host.getText().toString(), port.getText().toString(), model.getText().toString(), pin.getText().toString(), token.getText().toString(), runnerUrl.getText().toString(), runnerPin.getText().toString(), runnerToken.getText().toString(), nc);
        Profile saved = app.profiles.get(id);
        VictorLinux.Capabilities cap = found[0];
        if (cap != null && foundFor[0].equals(saved.base + "|" + saved.pin) && cap.advertised() != null) app.profiles.recordTested(id, "advertised", cap.advertised());
        if (app.profiles.defaultProfile() == null || app.profiles.all().size() == 2) app.profiles.setDefault(id);
        reports.remove(id);
        h.refresh();
        return true;
      } catch (IllegalArgumentException | IllegalStateException | JSONException e) {
        err.setText("⚠ " + (e.getMessage() == null ? "Could not save. Nothing was changed." : e.getMessage()));
        err.setVisibility(View.VISIBLE);
        return false;
      }
    }, null);
    if (existing != null) {
      f.addView(ui.button("Remove stored key", () -> ui.confirm("Remove key?", "The key for " + existing.name + " will be deleted from this device.", "Remove", () -> { app.secrets.remove(existing.id); h.refresh(); }), false));
      f.addView(ui.button("Delete this profile", () -> ui.confirm("Delete profile?", "Bots using it go back to the default. Its keys are deleted.", "Delete", () -> { app.profiles.delete(existing.id); h.refresh(); }), false));
    }
  }

  /** The stored token for the address and pin currently typed, when it is still readable for them. */
  private String storedToken(Profile existing, EditText host, EditText port, EditText pin) {
    if (existing == null) return "";
    try {
      String binding = Profile.binding(Policy.Kind.PC, VictorLinux.baseUrl(host.getText().toString(), port.getText().toString()), Rules.pin(pin.getText().toString()));
      return app.secrets.read(existing.id, binding);
    } catch (SecurityException e) {
      return "";
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      return "";
    }
  }

  private void discover(Profile existing, EditText base, EditText model) {
    if (existing == null) {
      ui.info("Save first", "Save the profile with its key, then edit it and use Discover.");
      return;
    }
    app.runAsync(() -> {
      Diagnostics.Report r = app.diagnostics.probe(app.profiles.get(existing.id));
      reports.put(existing.id, r);
      h.act().runOnUiThread(() -> {
        if (r.models.isEmpty()) ui.info("No model list", "This server did not list models (" + r.model.detail + "). Type the model id manually.");
        else {
          String[] items = r.models.toArray(new String[0]);
          ui.choose("Models on this server", items, w -> model.setText(items[w]));
        }
      });
    });
  }

  void renderUsage(LinearLayout body) {
    body.addView(ui.text("USAGE", 11, Ui.ACCENT));
    body.addView(ui.heading("Every request, with where it went.", 28));
    body.addView(ui.text(UsageStats.totals(app.db), 13, Ui.MUTED));
    body.addView(ui.text("Tokens come only from what the server reported; “unknown” means it did not say. Costs are estimates from rates you enter, never invoices.", 12, Ui.MUTED));
    for (UsageStats.Row r : UsageStats.recent(app.db, 60)) {
      LinearLayout c = ui.card(body);
      c.addView(ui.text(r.when + " · " + r.kind, 10, Ui.ACCENT));
      c.addView(ui.text(r.line(), 12, r.ok ? Ui.FG : Ui.WARN));
    }
  }
}
