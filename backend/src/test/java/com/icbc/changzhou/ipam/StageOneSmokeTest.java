package com.icbc.changzhou.ipam;

import java.nio.file.*;
import java.sql.*;
import org.mindrot.jbcrypt.BCrypt;

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
    Path recoveryDb=work.resolve("recovery.db"), passwordFile=work.resolve("one-time-password");
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+recoveryDb);Statement s=c.createStatement()){
      s.execute("CREATE TABLE sys_user(id INTEGER PRIMARY KEY,username TEXT,password_hash TEXT,real_name TEXT,role TEXT,is_active INTEGER,must_change_password INTEGER,failed_login_count INTEGER,locked_until TEXT,password_changed_at TEXT,updated_at TEXT)");
      s.execute("CREATE TABLE sys_session(id INTEGER PRIMARY KEY,user_id INTEGER,revoked_at TEXT)");
      s.execute("CREATE TABLE audit_log(id INTEGER PRIMARY KEY,action TEXT,target_type TEXT,target_id TEXT,result TEXT,detail_json TEXT)");
      s.execute("INSERT INTO sys_user(id,username,password_hash,real_name,role,is_active,must_change_password,failed_login_count) VALUES(1,'admin','"+BCrypt.hashpw("OldPassword2026",BCrypt.gensalt(4))+"','管理员','ADMIN',0,0,5)");
      s.execute("INSERT INTO sys_session(id,user_id) VALUES(1,1)");
    }
    Files.writeString(passwordFile,"123456",java.nio.charset.StandardCharsets.UTF_8);
    DatabaseTool.resetAdmin(recoveryDb,passwordFile,"ignored");
    if(Files.exists(passwordFile))throw new AssertionError("恢复口令文件未删除");
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+recoveryDb);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT password_hash,is_active,must_change_password,failed_login_count,locked_until FROM sys_user WHERE id=1")){if(!r.next()||!BCrypt.checkpw("123456",r.getString(1))||r.getInt(2)!=1||r.getInt(3)!=1||r.getInt(4)!=0||r.getString(5)!=null)throw new AssertionError("管理员恢复状态错误");}
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+recoveryDb);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT revoked_at FROM sys_session WHERE id=1")){if(!r.next()||r.getString(1)==null)throw new AssertionError("管理员恢复未撤销会话");}
    System.out.println("PASS|ADMIN_RECOVERY_SMOKE|"+recoveryDb);
    System.out.println("PASS|STAGE1_SQLITE_SMOKE|"+work);
  }
}
