package com.victorious.bots;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage Access Framework side of per-bot folders. Pure rules (names, states, export text) are in
 * {@link BotFolders}. This class only: asks Android for one folder tree, keeps or releases that grant, finds or
 * creates a bot's subfolder, and writes new files into it. It never reads file contents, never deletes, never
 * asks for broad storage permissions, and refuses to write when the grant is missing.
 */
final class BotFolderAccess {
  private static final int RW = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
  /** Share sheet text is kept well under Binder transaction limits. */
  static final int MAX_SHARE_CHARS = 100_000;
  /** Same bound as a verified job artifact download. */
  static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

  private BotFolderAccess() {}

  /** The system folder picker. The owner chooses; VictorBots suggests nothing and reads nothing. */
  static Intent picker() {
    return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(RW | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
  }

  /** Keeps the grant for the chosen tree and releases a previously chosen, different tree. */
  static void accept(Context c, SharedPreferences prefs, Uri tree) {
    ContentResolver r = c.getContentResolver();
    r.takePersistableUriPermission(tree, RW);
    String old = prefs.getString(BotFolders.PREF_TREE, "");
    if (!old.isEmpty() && !old.equals(tree.toString())) release(r, old);
    prefs.edit().putString(BotFolders.PREF_TREE, tree.toString()).apply();
  }

  /** Forgets the folder and gives the grant back. Files already written stay where they are. */
  static void forget(Context c, SharedPreferences prefs) {
    String old = prefs.getString(BotFolders.PREF_TREE, "");
    if (!old.isEmpty()) release(c.getContentResolver(), old);
    prefs.edit().remove(BotFolders.PREF_TREE).apply();
  }

  private static void release(ContentResolver r, String uri) {
    try {
      r.releasePersistableUriPermission(Uri.parse(uri), RW);
    } catch (SecurityException ignored) {
      // Already gone (revoked in settings); nothing left to release.
    }
  }

  static BotFolders.State state(Context c, SharedPreferences prefs) {
    List<String> granted = new ArrayList<>();
    for (UriPermission p : c.getContentResolver().getPersistedUriPermissions())
      if (p.isReadPermission() && p.isWritePermission()) granted.add(p.getUri().toString());
    return BotFolders.state(prefs.getString(BotFolders.PREF_TREE, ""), granted);
  }

  /** A short label for the chosen folder (its last path part), or "" when none is chosen. */
  static String label(SharedPreferences prefs) {
    String t = prefs.getString(BotFolders.PREF_TREE, "");
    if (t.isEmpty()) return "";
    try {
      String id = DocumentsContract.getTreeDocumentId(Uri.parse(t));
      int colon = id.lastIndexOf(':');
      return colon >= 0 && colon + 1 < id.length() ? id.substring(colon + 1) : id;
    } catch (IllegalArgumentException e) {
      return "(unknown folder)";
    }
  }

  private static Uri requireGranted(Context c, SharedPreferences prefs) throws IOException {
    BotFolders.State s = state(c, prefs);
    if (s != BotFolders.State.OK) throw new IOException(BotFolders.describe(s));
    return Uri.parse(prefs.getString(BotFolders.PREF_TREE, ""));
  }

  /** Finds or creates {@code <bot-name>-<short-uid>} directly inside the chosen tree. */
  private static Uri botFolder(ContentResolver r, Uri tree, String folder) throws IOException {
    if (!BotFolders.safeSegment(folder)) throw new IOException("Unsafe folder name.");
    String rootId = DocumentsContract.getTreeDocumentId(tree);
    Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
    String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
    try (Cursor cur = r.query(children, cols, null, null, null)) {
      while (cur != null && cur.moveToNext())
        if (folder.equals(cur.getString(1)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(2)))
          return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0));
    }
    Uri made = DocumentsContract.createDocument(r, root, DocumentsContract.Document.MIME_TYPE_DIR, folder);
    if (made == null) throw new IOException("Could not create the bot's folder.");
    return made;
  }

  /**
   * Writes a NEW file into the bot's own folder and returns "folder/name" for display. The provider renames on a
   * clash, so an existing file is never overwritten.
   */
  static String write(Context c, SharedPreferences prefs, String botName, String botUid, String fileName, String mime, byte[] data) throws IOException {
    if (data.length > MAX_FILE_BYTES) throw new IOException("This file is larger than the " + (MAX_FILE_BYTES / 1048576) + " MB limit.");
    Uri tree = requireGranted(c, prefs);
    String name = BotFolders.fileName(fileName, "victorbots-file.txt");
    if (!BotFolders.safeSegment(name)) throw new IOException("Unsafe file name.");
    String folder = BotFolders.folderName(botName, botUid);
    ContentResolver r = c.getContentResolver();
    Uri dir = botFolder(r, tree, folder);
    Uri doc = DocumentsContract.createDocument(r, dir, mime, name);
    if (doc == null) throw new IOException("Could not create the file.");
    try (OutputStream out = r.openOutputStream(doc, "w")) {
      if (out == null) throw new IOException("Could not open the new file.");
      out.write(data);
    }
    return folder + "/" + name;
  }

  /** The standard Android share sheet for plain text (VictorFiles and any other target may appear). */
  static Intent shareText(String subject, String text) {
    String t = text == null ? "" : text;
    if (t.length() > MAX_SHARE_CHARS) t = t.substring(0, MAX_SHARE_CHARS) + "\n…[cut for sharing]";
    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t).putExtra(Intent.EXTRA_SUBJECT, subject);
    return Intent.createChooser(send, "Share to…");
  }
}
