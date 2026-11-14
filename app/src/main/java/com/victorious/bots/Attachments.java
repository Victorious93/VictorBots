package com.victorious.bots;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Intake rules for owner-selected files. Text/source and logs are read as UTF-8 data, images only for
 * profiles that enable them, and everything else is refused with a plain reason. File content is data:
 * it is always wrapped as untrusted when sent to a model.
 */
public final class Attachments {
  public static final int MAX_READ_BYTES = 1024 * 1024;       // never read more than this from a file
  public static final int MAX_SOURCE_CHARS = 60_000;          // source files over this are refused, not cut
  public static final int LOG_HEAD = 8_000, LOG_TAIL = 40_000; // logs are shortened visibly, keeping the end
  public static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
  public static final int MAX_FILES = 5;

  public static final class Result {
    public String kind = "unsupported"; // text | image | unsupported
    public String name, mime, sha256, text = "", dataUri = "", reason = "";
    public long size;
    public boolean truncated;
  }

  private static final Set<String> LOG_EXT = new HashSet<>(Arrays.asList("log", "txt", "trace", "out", "crash"));
  private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList("java", "kt", "kts", "xml", "gradle", "json", "md", "yml", "yaml", "toml", "properties", "sh", "py", "js", "ts", "c", "cc", "cpp", "h", "hpp", "rs", "go", "cs", "swift", "sql", "html", "css", "csv", "ini", "cfg", "conf", "diff", "patch", "pro", "txt", "log", "smali"));
  private static final Map<String, String> UNSUPPORTED = new HashMap<>();

  static {
    UNSUPPORTED.put("pdf", "PDF files need a real PDF text parser, and none is built in. Export the text or paste it.");
    UNSUPPORTED.put("docx", "Word documents are not supported yet (no document parser is built in).");
    UNSUPPORTED.put("doc", "Word documents are not supported yet.");
    UNSUPPORTED.put("xlsx", "Spreadsheets are not supported yet. Export as CSV.");
    UNSUPPORTED.put("zip", "Archives are not opened. Select the files inside.");
    UNSUPPORTED.put("apk", "APK files are not read by chat. Use a PC job to inspect one.");
    UNSUPPORTED.put("gz", "Compressed files are not opened.");
    UNSUPPORTED.put("jar", "Java archives are not opened.");
    UNSUPPORTED.put("so", "Native libraries are not readable as text.");
    UNSUPPORTED.put("dex", "DEX files are not readable as text.");
    UNSUPPORTED.put("mp4", "Video is not supported.");
    UNSUPPORTED.put("mp3", "Audio is not supported.");
  }

  static String ext(String name) {
    int i = name.lastIndexOf('.');
    return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
  }

  static String sha(byte[] b) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
      StringBuilder s = new StringBuilder();
      for (byte x : d) s.append(String.format(Locale.ROOT, "%02x", x));
      return s.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Image type from magic bytes, or "" (the declared MIME type and extension are not trusted). */
  public static String imageType(byte[] b) {
    if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
    if (b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
    if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
    if (b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
    return "";
  }

  /** Display-safe file name: no path, bounded, printable. */
  public static String cleanName(String raw) {
    String n = raw == null ? "file" : raw.replace('\\', '/');
    n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
    if (n.isEmpty()) n = "file";
    return n.length() > 100 ? n.substring(0, 100) : n;
  }

  public static Result classify(String rawName, byte[] bytes, boolean sourceWasCut) {
    Result r = new Result();
    r.name = cleanName(rawName);
    r.size = bytes.length;
    r.sha256 = sha(bytes);
    String ext = ext(r.name);
    String img = imageType(bytes);
    if (!img.isEmpty()) {
      r.mime = img;
      if (bytes.length > MAX_IMAGE_BYTES) {
        r.reason = "Images over " + (MAX_IMAGE_BYTES / 1048576) + " MB are not accepted.";
        return r;
      }
      r.kind = "image";
      r.dataUri = "data:" + img + ";base64," + Base64.getEncoder().encodeToString(bytes);
      return r;
    }
    if (UNSUPPORTED.containsKey(ext)) {
      r.mime = "application/octet-stream";
      r.reason = UNSUPPORTED.get(ext);
      return r;
    }
    if (bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
      r.reason = UNSUPPORTED.get("pdf");
      return r;
    }
    if (bytes.length > 2 && bytes[0] == 'P' && bytes[1] == 'K') {
      r.reason = "This looks like a ZIP-based file (archive, Office document or APK). It is not supported.";
      return r;
    }
    int probe = Math.min(bytes.length, 8192);
    for (int i = 0; i < probe; i++)
      if (bytes[i] == 0) {
        r.reason = "This is a binary file. Only text, source files, logs and images are supported.";
        return r;
      }
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException e) {
      r.reason = "This file is not valid UTF-8 text.";
      return r;
    }
    r.mime = "text/plain";
    boolean log = LOG_EXT.contains(ext) && !TEXT_EXT.contains(ext) || ext.equals("log") || ext.equals("trace") || ext.equals("crash");
    if (!TEXT_EXT.contains(ext) && !ext.isEmpty() && !LOG_EXT.contains(ext)) {
      // Unknown extension but valid text: accept, it is still only data.
    }
    if (sourceWasCut) {
      r.reason = "This file is larger than the " + (MAX_READ_BYTES / 1024) + " KB read limit.";
      if (!log) return r;
    }
    if (text.length() > MAX_SOURCE_CHARS) {
      if (!log) {
        r.reason = "This file has " + text.length() + " characters; the limit for source files is " + MAX_SOURCE_CHARS + ". Select a smaller part. Nothing was cut silently.";
        return r;
      }
      text = text.substring(0, LOG_HEAD) + "\n…[" + (text.length() - LOG_HEAD - LOG_TAIL) + " characters omitted from the middle of this log]…\n" + text.substring(text.length() - LOG_TAIL);
      r.truncated = true;
    }
    r.kind = "text";
    r.text = text;
    r.reason = "";
    return r;
  }

  private Attachments() {}
}
