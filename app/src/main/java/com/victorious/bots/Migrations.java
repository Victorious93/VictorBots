package com.victorious.bots;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit, ordered schema migrations. Fresh installs create the version 1 schema and then run the
 * same migrations as upgraded installs, so there is one path to test. Callers must disable foreign
 * keys while migrating (the table rebuild below follows SQLite's documented procedure) and run
 * foreign_key_check afterwards.
 */
public final class Migrations {
  public static final int VERSION = 2;

  /** The schema shipped in VictorBots 0.1.0 (database version 1), unchanged. */
  public static List<String> v1() {
    List<String> s = new ArrayList<>();
    s.add(
        "CREATE TABLE bots(id INTEGER PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL,"
            + "instructions TEXT NOT NULL,avatar TEXT NOT NULL,image TEXT NOT NULL DEFAULT '')");
    s.add(
        "CREATE TABLE chats(id INTEGER PRIMARY KEY,bot INTEGER NOT NULL REFERENCES bots(id) ON"
            + " DELETE CASCADE,title TEXT NOT NULL,created INTEGER NOT NULL)");
    s.add(
        "CREATE TABLE messages(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chats(id)"
            + " ON DELETE CASCADE,role TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,"
            + "created INTEGER NOT NULL)");
    s.add(
        "CREATE TABLE tasks(id INTEGER PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,status"
            + " TEXT NOT NULL,created INTEGER NOT NULL)");
    return s;
  }

  private static final String UID = "lower(hex(randomblob(16)))";

  public static List<String> v2() {
    List<String> s = new ArrayList<>();
    // --- bots: stable ids for import/export, per-bot provider/model/policy, project scope ---
    s.add("ALTER TABLE bots ADD COLUMN uid TEXT NOT NULL DEFAULT ''");
    s.add("UPDATE bots SET uid=" + UID);
    s.add("ALTER TABLE bots ADD COLUMN profile INTEGER");
    s.add("ALTER TABLE bots ADD COLUMN model TEXT NOT NULL DEFAULT ''");
    s.add("ALTER TABLE bots ADD COLUMN policy TEXT NOT NULL DEFAULT ''");
    s.add("ALTER TABLE bots ADD COLUMN project TEXT NOT NULL DEFAULT ''");
    s.add("ALTER TABLE bots ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''");
    s.add("ALTER TABLE bots ADD COLUMN role_hint TEXT NOT NULL DEFAULT ''");
    s.add("CREATE UNIQUE INDEX bots_uid ON bots(uid)");
    // --- provider profiles ---
    s.add(
        "CREATE TABLE profiles(id INTEGER PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL,"
            + "base TEXT NOT NULL DEFAULT '',model TEXT NOT NULL DEFAULT '',pin TEXT NOT NULL"
            + " DEFAULT '',caps TEXT NOT NULL DEFAULT '{}',tested TEXT NOT NULL DEFAULT '{}',"
            + "created INTEGER NOT NULL)");
    s.add("CREATE UNIQUE INDEX profiles_name ON profiles(name)");
    // --- chats rebuilt so team chats need no owning bot (SQLite 12-step procedure) ---
    s.add(
        "CREATE TABLE chats_new(id INTEGER PRIMARY KEY,bot INTEGER REFERENCES bots(id) ON DELETE"
            + " CASCADE,title TEXT NOT NULL,created INTEGER NOT NULL,kind TEXT NOT NULL DEFAULT"
            + " 'dm',config TEXT NOT NULL DEFAULT '{}',summary TEXT NOT NULL DEFAULT '',uid TEXT"
            + " NOT NULL DEFAULT '')");
    s.add(
        "INSERT INTO chats_new(id,bot,title,created,uid) SELECT id,bot,title,created,"
            + UID
            + " FROM chats");
    s.add("DROP TABLE chats");
    s.add("ALTER TABLE chats_new RENAME TO chats");
    s.add("CREATE UNIQUE INDEX chats_uid ON chats(uid)");
    s.add("CREATE INDEX chats_bot ON chats(bot)");
    s.add("CREATE INDEX messages_chat ON messages(chat,id)");
    s.add("ALTER TABLE messages ADD COLUMN author INTEGER NOT NULL DEFAULT 0");
    s.add("ALTER TABLE messages ADD COLUMN meta TEXT NOT NULL DEFAULT '{}'");
    s.add("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'");
    s.add("ALTER TABLE tasks ADD COLUMN uid TEXT NOT NULL DEFAULT ''");
    s.add("UPDATE tasks SET uid=" + UID);
    s.add(
        "CREATE TABLE chat_members(chat INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,"
            + "bot INTEGER NOT NULL REFERENCES bots(id) ON DELETE CASCADE,muted INTEGER NOT NULL"
            + " DEFAULT 0,PRIMARY KEY(chat,bot))");
    s.add(
        "CREATE TABLE rounds(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chats(id) ON"
            + " DELETE CASCADE,started INTEGER NOT NULL,finished INTEGER,state TEXT NOT NULL,"
            + "reason TEXT NOT NULL DEFAULT '',turns INTEGER NOT NULL DEFAULT 0,tokens INTEGER NOT"
            + " NULL DEFAULT 0,tokens_estimated INTEGER NOT NULL DEFAULT 0)");
    // --- usage ledger: every outbound inference call, with its destination ---
    s.add(
        "CREATE TABLE usage(id INTEGER PRIMARY KEY,ts INTEGER NOT NULL,bot INTEGER,profile"
            + " INTEGER,purpose TEXT NOT NULL,host TEXT NOT NULL,kind TEXT NOT NULL,requested_model"
            + " TEXT NOT NULL DEFAULT '',reported_model TEXT NOT NULL DEFAULT '',in_tokens INTEGER,"
            + "out_tokens INTEGER,latency_ms INTEGER,ttft_ms INTEGER,ok INTEGER NOT NULL,error"
            + " TEXT NOT NULL DEFAULT '')");
    s.add("CREATE INDEX usage_ts ON usage(ts)");
    // --- memory (owner-editable, scoped, with provenance) ---
    s.add(
        "CREATE TABLE memories(id INTEGER PRIMARY KEY,uid TEXT NOT NULL UNIQUE,scope TEXT NOT"
            + " NULL,bot INTEGER REFERENCES bots(id) ON DELETE CASCADE,project TEXT NOT NULL"
            + " DEFAULT '',body TEXT NOT NULL,origin TEXT NOT NULL,status TEXT NOT NULL,"
            + "source_chat INTEGER REFERENCES chats(id) ON DELETE SET NULL,source_msg INTEGER,"
            + "source_label TEXT NOT NULL DEFAULT '',pinned INTEGER NOT NULL DEFAULT 0,created"
            + " INTEGER NOT NULL,updated INTEGER NOT NULL)");
    s.add("CREATE INDEX memories_bot ON memories(bot,status)");
    s.add("CREATE VIRTUAL TABLE memory_fts USING fts4(body)");
    // --- jobs: persistent, correlated, backend-reported ---
    s.add(
        "CREATE TABLE jobs(id INTEGER PRIMARY KEY,uid TEXT NOT NULL UNIQUE,request_id TEXT NOT"
            + " NULL UNIQUE,title TEXT NOT NULL,bot INTEGER REFERENCES bots(id) ON DELETE SET"
            + " NULL,chat INTEGER REFERENCES chats(id) ON DELETE SET NULL,source_msg INTEGER,"
            + "backend TEXT NOT NULL,profile INTEGER,tool TEXT NOT NULL DEFAULT '',args TEXT NOT"
            + " NULL DEFAULT '{}',state TEXT NOT NULL,state_source TEXT NOT NULL DEFAULT 'local',"
            + "remote_id TEXT NOT NULL DEFAULT '',last_seq INTEGER NOT NULL DEFAULT 0,depends_on"
            + " INTEGER REFERENCES jobs(id) ON DELETE SET NULL,proposed_by INTEGER,created INTEGER"
            + " NOT NULL,updated INTEGER NOT NULL,started INTEGER,finished INTEGER,error TEXT NOT"
            + " NULL DEFAULT '',result TEXT NOT NULL DEFAULT '{}')");
    s.add("CREATE INDEX jobs_state ON jobs(state)");
    s.add(
        "CREATE TABLE job_events(job INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,seq"
            + " INTEGER NOT NULL,ts INTEGER NOT NULL,stream TEXT NOT NULL,text TEXT NOT NULL,"
            + "PRIMARY KEY(job,seq))");
    s.add(
        "CREATE TABLE artifacts(id INTEGER PRIMARY KEY,job INTEGER NOT NULL REFERENCES jobs(id) ON"
            + " DELETE CASCADE,remote_name TEXT NOT NULL,kind TEXT NOT NULL,size INTEGER NOT NULL,"
            + "sha256 TEXT NOT NULL,mime TEXT NOT NULL DEFAULT '',local_path TEXT NOT NULL DEFAULT"
            + " '',verified INTEGER NOT NULL DEFAULT 0,UNIQUE(job,remote_name))");
    // --- skills ---
    s.add(
        "CREATE TABLE skills(id INTEGER PRIMARY KEY,uid TEXT NOT NULL UNIQUE,name TEXT NOT NULL"
            + " UNIQUE,description TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 1,body TEXT NOT"
            + " NULL,requires TEXT NOT NULL DEFAULT '[]',builtin INTEGER NOT NULL DEFAULT 0,"
            + "created INTEGER NOT NULL,updated INTEGER NOT NULL)");
    // --- attachments (owner-selected files) ---
    s.add(
        "CREATE TABLE attachments(id INTEGER PRIMARY KEY,message INTEGER NOT NULL REFERENCES"
            + " messages(id) ON DELETE CASCADE,name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER"
            + " NOT NULL,sha256 TEXT NOT NULL,kind TEXT NOT NULL,body TEXT NOT NULL DEFAULT '',"
            + "path TEXT NOT NULL DEFAULT '',truncated INTEGER NOT NULL DEFAULT 0)");
    // --- routines ---
    s.add(
        "CREATE TABLE routines(id INTEGER PRIMARY KEY,uid TEXT NOT NULL UNIQUE,name TEXT NOT"
            + " NULL,bot INTEGER NOT NULL REFERENCES bots(id) ON DELETE CASCADE,target TEXT NOT"
            + " NULL,profile INTEGER,instruction TEXT NOT NULL,tool TEXT NOT NULL DEFAULT '',args"
            + " TEXT NOT NULL DEFAULT '{}',tz TEXT NOT NULL,schedule TEXT NOT NULL,enabled INTEGER"
            + " NOT NULL DEFAULT 0,overlap TEXT NOT NULL DEFAULT 'skip',missed TEXT NOT NULL"
            + " DEFAULT 'skip',next_run INTEGER,remote_id TEXT NOT NULL DEFAULT '',created INTEGER"
            + " NOT NULL)");
    s.add(
        "CREATE TABLE routine_runs(id INTEGER PRIMARY KEY,routine INTEGER NOT NULL REFERENCES"
            + " routines(id) ON DELETE CASCADE,scheduled INTEGER NOT NULL,started INTEGER,finished"
            + " INTEGER,status TEXT NOT NULL,job INTEGER REFERENCES jobs(id) ON DELETE SET NULL,"
            + "error TEXT NOT NULL DEFAULT '',UNIQUE(routine,scheduled))");
    // --- per-bot connector enablement ---
    s.add(
        "CREATE TABLE bot_connectors(bot INTEGER NOT NULL REFERENCES bots(id) ON DELETE CASCADE,"
            + "connector TEXT NOT NULL,profile INTEGER NOT NULL,tools TEXT NOT NULL DEFAULT"
            + " '[]',PRIMARY KEY(bot,connector))");
    return s;
  }

  public static void run(Db db, List<String> sql) {
    for (String s : sql) db.exec(s);
  }

  /** Brings a database at {@code from} up to {@link #VERSION}. Caller wraps this in a transaction. */
  public static void upgrade(Db db, int from) {
    if (from < 1) run(db, v1());
    if (from < 2) run(db, v2());
  }

  private Migrations() {}
}
