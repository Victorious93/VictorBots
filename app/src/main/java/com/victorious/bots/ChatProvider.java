package com.victorious.bots;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Per-chat provider switch (roadmap item 8a, PR order step 2). The owner can pick, for one direct
 * chat, a different saved provider profile (the same-phone DroidCommand COMPANION_ASSIST path or an
 * HTTPS OpenAI-compatible profile) without editing the bot. Nothing picked means the chat inherits the
 * bot's provider, and a bot without one inherits the workspace default.
 *
 * <p>The choice is only a destination hint handed to {@link Gateway} as the request's profile; Gateway
 * still applies policy first (the bot's stricter policy and the owner's global policy), so a switch
 * can never loosen policy. Team chats, routines, memory, summaries and routing are not affected.
 * The choice is stored in {@code chats.profile} (schema v5) and holds only a local profile id: no
 * key, no endpoint, and it is not exported in backups.
 */
public final class ChatProvider {
  public enum Source { CHAT, BOT, DEFAULT, NONE }

  /** What the chat screen shows: the profile that would be asked, and why. */
  public static final class Effective {
    public Profile profile;
    public Source source = Source.NONE;

    /** English wording for the chat header, e.g. "Provider: My PC · chosen for this chat". */
    public String describe() {
      if (profile == null) return "Provider: none configured";
      return "Provider: " + profile.name + " · " + why(source);
    }
  }

  public static String why(Source s) {
    switch (s) {
      case CHAT: return "chosen for this chat";
      case BOT: return "inherited from this bot";
      case DEFAULT: return "inherited from the workspace default";
      default: return "not set";
    }
  }

  /** The profile id the owner picked for this chat, or 0 when none is picked or it no longer exists. */
  public static long chosen(Db db, long chatId) {
    if (chatId == 0) return 0;
    JSONObject r = db.one("SELECT c.profile p FROM chats c JOIN profiles p ON p.id=c.profile WHERE c.id=? AND c.kind='dm'", chatId);
    return r == null ? 0 : r.optLong("p", 0);
  }

  /**
   * The profile override to pass to {@link Gateway} for an owner-initiated chat request. Returns 0
   * (use the bot's own resolution, including the bot's model override) when nothing is picked or the
   * pick is the bot's own profile.
   */
  public static long route(Db db, long chatId, long botId) {
    long pick = chosen(db, chatId);
    if (pick == 0) return 0;
    JSONObject bot = botId == 0 ? null : db.one("SELECT profile FROM bots WHERE id=?", botId);
    if (bot != null && bot.optLong("profile", 0) == pick) return 0;
    return pick;
  }

  /** Resolves what the chat would use and why, without any network activity. */
  public static Effective effective(Db db, Profiles profiles, long chatId, long botId) {
    Effective e = new Effective();
    long pick = chosen(db, chatId);
    if (pick != 0) {
      e.profile = profiles.get(pick);
      e.source = Source.CHAT;
      return e;
    }
    JSONObject bot = botId == 0 ? null : db.one("SELECT profile FROM bots WHERE id=?", botId);
    long bp = bot == null ? 0 : bot.optLong("profile", 0);
    Profile p = bp == 0 ? null : profiles.get(bp);
    if (p != null) {
      e.profile = p;
      e.source = Source.BOT;
      return e;
    }
    e.profile = profiles.defaultProfile();
    e.source = e.profile == null ? Source.NONE : Source.DEFAULT;
    return e;
  }

  /**
   * Stores the owner's pick for one direct chat; 0 clears it (inherit). Never edits the bot, the
   * workspace default or any policy. Refuses team chats and unknown profiles.
   */
  public static void set(Db db, Profiles profiles, long chatId, long profileId) {
    JSONObject c = db.one("SELECT kind FROM chats WHERE id=?", chatId);
    if (c == null) throw new IllegalArgumentException("Unknown conversation.");
    if (!"dm".equals(c.optString("kind", "dm"))) throw new IllegalArgumentException("Team chats use each member bot's own provider.");
    if (profileId != 0 && profiles.get(profileId) == null) throw new IllegalArgumentException("Unknown provider profile.");
    db.update("UPDATE chats SET profile=? WHERE id=?", profileId == 0 ? null : (Object) profileId, chatId);
  }

  /** One row of the chat's provider picker. {@code id} 0 is "inherit". */
  public static final class Option {
    public final long id;
    public final String label;

    Option(long id, String label) {
      this.id = id;
      this.label = label;
    }
  }

  /** Picker rows: "inherit" first (naming what it inherits), then every saved profile. */
  public static List<Option> options(Db db, Profiles profiles, long botId) {
    List<Option> out = new ArrayList<>();
    Effective inherit = effective(db, profiles, 0, botId);
    out.add(new Option(0, "Inherit (" + (inherit.profile == null ? "none configured" : inherit.profile.name + ", " + why(inherit.source).replace("inherited from ", "from ")) + ")"));
    for (Profile p : profiles.all()) out.add(new Option(p.id, p.describe()));
    return out;
  }

  private ChatProvider() {}
}
