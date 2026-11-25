import com.victorious.bots.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-bot folders (roadmap PR order step 8): folder-safe names, grant states, chat export, backup exclusion. */
public final class BotFoldersTest {
  static final TimeZone NY = TimeZone.getTimeZone("America/New_York");

  public static void main(String[] a) throws Exception {
    // 1. Grant state: nothing chosen -> nothing written; chosen but not persisted -> revoked, said plainly.
    String tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FVictorBots";
    T.eq("no folder chosen", BotFolders.State.NONE, BotFolders.state(null, Collections.singleton(tree)));
    T.eq("blank folder is none", BotFolders.State.NONE, BotFolders.state("  ", Collections.singleton(tree)));
    T.eq("granted", BotFolders.State.OK, BotFolders.state(tree, Arrays.asList("content://other/tree/x", tree)));
    T.eq("grant removed in settings is detected", BotFolders.State.REVOKED, BotFolders.state(tree, Collections.<String>emptyList()));
    T.eq("null grant list is revoked", BotFolders.State.REVOKED, BotFolders.state(tree, null));
    T.check("revoked wording tells the owner", BotFolders.describe(BotFolders.State.REVOKED).contains("no longer has access"));
    T.check("none wording says nothing is written", BotFolders.describe(BotFolders.State.NONE).contains("Nothing is written"));

    // 2. Folder names: <bot-name>-<short-uid>, never able to escape the chosen folder.
    String uid = "A1b2C3d4E5f6a7b8c9d0e1f2a3b4c5d6";
    T.eq("plain name", "Coach-a1b2c3d4", BotFolders.folderName("Coach", uid));
    T.eq("spaces kept", "DroidCommand Coach-a1b2c3d4", BotFolders.folderName("DroidCommand Coach", uid));
    String[] evil = {"../../etc", "..", ".", "a/b", "a\\b", "/abs", "con", "NUL", "x\u0000y", "\n\r\t", "...hidden", "name:with*bad?chars", "  ", "", null, "\u202Eevil", "a/../../b", "%2e%2e%2f"};
    for (String n : evil) {
      String f = BotFolders.folderName(n, uid);
      T.check("folder safe for " + String.valueOf(n).replaceAll("\\p{Cntrl}", "?") + " -> " + f, BotFolders.safeSegment(f) && !f.contains("/") && !f.contains("\\") && !f.contains("..") && !f.startsWith("."));
      T.check("folder keeps uid for " + String.valueOf(n).replaceAll("\\p{Cntrl}", "?"), f.endsWith("-a1b2c3d4"));
    }
    T.eq("traversal neutralised", "etc-a1b2c3d4", BotFolders.folderName("../../etc", uid));
    T.eq("reserved device name replaced", "bot-a1b2c3d4", BotFolders.folderName("con", uid));
    T.eq("empty name falls back", "bot-a1b2c3d4", BotFolders.folderName("", uid));
    T.eq("uid stripped to letters and digits", "Coach-ab12", BotFolders.folderName("Coach", "-a/b.1\\2"));
    T.eq("no uid", "Coach", BotFolders.folderName("Coach", null));
    String longName = BotFolders.folderName(new String(new char[300]).replace('\0', 'x'), uid);
    T.check("long name bounded", longName.length() <= BotFolders.MAX_NAME + 1 + BotFolders.UID_CHARS);
    T.eq("non-Latin letters kept", "Ассистент-a1b2c3d4", BotFolders.folderName("Ассистент", uid));
    T.check("two bots with one name get two folders", !BotFolders.folderName("Coach", uid).equals(BotFolders.folderName("Coach", "ffff0000" + uid)));

    // 3. File names inside a bot folder.
    T.eq("plain file", "report.txt", BotFolders.fileName("report.txt", "x.txt"));
    T.eq("directory part dropped", "passwd", BotFolders.fileName("../../etc/passwd", "x.txt"));
    T.eq("windows path dropped", "boot.ini", BotFolders.fileName("C:\\Windows\\boot.ini", "x.txt"));
    T.eq("dot file falls back", "fallback.md", BotFolders.fileName("..", "fallback.md"));
    T.eq("hidden name unhidden", "bashrc", BotFolders.fileName(".bashrc", "x"));
    T.eq("nothing usable", "victorbots-file.txt", BotFolders.fileName("/", "../"));
    T.check("file name bounded", BotFolders.fileName(new String(new char[500]).replace('\0', 'a') + ".txt", "x").length() <= BotFolders.MAX_FILE);
    T.check("safeSegment rejects separators", !BotFolders.safeSegment("a/b") && !BotFolders.safeSegment("a\\b") && !BotFolders.safeSegment(".."));
    T.check("safeSegment rejects control chars", !BotFolders.safeSegment("a\u0007b"));
    T.check("safeSegment rejects reserved with extension", !BotFolders.safeSegment("aux.txt"));
    T.check("safeSegment accepts normal", BotFolders.safeSegment("chat-Plan (v2)-20261125-1013.md"));

    // 4. Export names and content.
    long when = 1795619580000L; // 2026-11-25 10:13 New York (EST)
    T.eq("chat export name", "chat-Trip plan-20261125-1013.md", BotFolders.chatExportName("Trip plan", when, NY));
    T.eq("chat export name with slashes", "chat-a-b-20261125-1013.md", BotFolders.chatExportName("a/b", when, NY));
    T.eq("chat export name empty title", "chat-conversation-20261125-1013.md", BotFolders.chatExportName("", when, NY));
    T.eq("message name", "message-20261125-101300.md", BotFolders.messageName(when, NY));
    List<ChatHistory.Msg> msgs = Arrays.asList(
        new ChatHistory.Msg(1, "user", "done", "text", "First question"),
        new ChatHistory.Msg(2, "assistant", "replaced", "text", "OLD REPLY"),
        new ChatHistory.Msg(3, "assistant", "done", "text", "New reply"),
        new ChatHistory.Msg(4, "system", "error", "text", "FAILED"),
        new ChatHistory.Msg(5, "assistant", "pending", "text", "PENDING"));
    String md = BotFolders.chatExport("Coach", "Trip\nplan", msgs, when, NY);
    T.check("export has title on one line", md.startsWith("# Trip plan\n"));
    T.check("export names bot", md.contains("Bot: Coach"));
    T.check("export time in zone", md.contains("2026-11-25 10:13 EST"));
    T.check("export keeps done turns", md.contains("## You\n\nFirst question") && md.contains("## Coach\n\nNew reply"));
    T.check("export drops replaced", !md.contains("OLD REPLY"));
    T.check("export drops errors and pending", !md.contains("FAILED") && !md.contains("PENDING"));
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 2000; i++) big.append("0123456789");
    List<ChatHistory.Msg> many = new ArrayList<>();
    for (int i = 0; i < 400; i++) many.add(new ChatHistory.Msg(i, "user", "done", "text", big.toString()));
    String cut = BotFolders.chatExport("B", "T", many, when, NY);
    T.check("export bounded", cut.getBytes(StandardCharsets.UTF_8).length <= BotFolders.MAX_WRITE_BYTES && cut.contains("export cut"));

    // 5. The chosen location never reaches a backup: it is in preferences, not the database.
    Fakes.Env e = Fakes.env();
    e.kv.put(BotFolders.PREF_TREE, tree);
    String exported = Backup.export(e.db).toString();
    T.check("backup export has no tree URI", !exported.contains("content://") && !exported.contains(BotFolders.PREF_TREE));
    T.check("backup lists the folder as excluded", exported.contains("bot folder location"));
    String backupSrc = new String(Files.readAllBytes(Paths.get("app/src/main/java/com/victorious/bots/Backup.java")), StandardCharsets.UTF_8);
    T.check("Backup.java never reads the folder key", !backupSrc.contains("PREF_TREE") && !backupSrc.contains("bot_folder_tree") && !backupSrc.contains("BotFolderAccess"));

    // 6. Source rules for the Android side: SAF only, no broad storage, no reading from the folder, Gateway untouched.
    String acc = new String(Files.readAllBytes(Paths.get("app/src/main/java/com/victorious/bots/BotFolderAccess.java")), StandardCharsets.UTF_8);
    String manifest = new String(Files.readAllBytes(Paths.get("app/src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
    T.check("no broad storage permission", !manifest.contains("MANAGE_EXTERNAL_STORAGE") && !manifest.contains("WRITE_EXTERNAL_STORAGE") && !manifest.contains("READ_EXTERNAL_STORAGE"));
    T.check("access uses SAF tree grants", acc.contains("ACTION_OPEN_DOCUMENT_TREE") && acc.contains("takePersistableUriPermission"));
    T.check("access never opens files for reading", !acc.contains("openInputStream") && !acc.contains("\"r\""));
    T.check("access never deletes", !acc.contains("deleteDocument"));
    T.check("access checks the grant before writing", acc.contains("requireGranted"));
    T.check("access validates names", acc.contains("BotFolders.safeSegment"));
    T.check("no model call in folder code", !acc.contains("Provider") && !acc.contains("Gateway"));
    T.done();
  }
}
