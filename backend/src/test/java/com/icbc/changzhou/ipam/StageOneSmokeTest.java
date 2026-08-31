package com.icbc.changzhou.ipam;

import java.nio.file.*;
import java.sql.*;

/** Dependency and SQLite read/write/WAL/backup-restore smoke test; executable without JUnit. */
public final class StageOneSmokeTest {
  public static void main(String[] args) throws Exception {
    Path work=Files.createTempDirectory("ipam-stage1-"); Path db=work.resolve("smoke.db"), backup=work.resolve("backup.db");
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement()){
      s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000");
      ResultSet mode=s.executeQuery("PRAGMA journal_mode=WAL"); if(!mode.next()||!"wal".equalsIgnoreCase(mode.getString(1)))throw new AssertionError("WAL 未启用");
      s.execute("CREATE TABLE smoke(id INTEGER PRIMARY KEY, value TEXT NOT NULL)"); s.execute("INSERT INTO smoke(value) VALUES('arm64-jdbc-read-write')");
      ResultSet r=s.executeQuery("SELECT value FROM smoke WHERE id=1"); if(!r.next()||!"arm64-jdbc-read-write".equals(r.getString(1)))throw new AssertionError("SQLite 读写失败");
    }
    DatabaseTool.backup(db,backup);
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement()){s.execute("DELETE FROM smoke");}
    DatabaseTool.restore(db,backup);
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT value FROM smoke")){if(!r.next()||!"arm64-jdbc-read-write".equals(r.getString(1)))throw new AssertionError("备份恢复失败");}
    System.out.println("PASS|STAGE1_SQLITE_SMOKE|"+work);
  }
}
