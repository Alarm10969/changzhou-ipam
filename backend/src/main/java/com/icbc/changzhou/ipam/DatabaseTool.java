package com.icbc.changzhou.ipam;

import java.nio.file.*;
import java.sql.*;

/** Offline/operational SQLite utility: online backup, integrity check, and stopped-service restore. */
public final class DatabaseTool {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("用法: DatabaseTool check <db> | backup <db> <backup.db> | restore <db> <backup.db>");
    Path db=Paths.get(args[1]).toAbsolutePath().normalize();
    if("check".equals(args[0])) { check(db); return; }
    if(args.length!=3) throw new IllegalArgumentException("backup/restore 需要数据库和备份文件路径。");
    Path backup=Paths.get(args[2]).toAbsolutePath().normalize();
    if("backup".equals(args[0])) backup(db,backup); else if("restore".equals(args[0])) restore(db,backup); else throw new IllegalArgumentException("未知命令。");
  }
  static void check(Path db)throws Exception {try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA integrity_check")){if(!r.next()||!"ok".equalsIgnoreCase(r.getString(1)))throw new IllegalStateException("SQLite integrity_check 失败。");System.out.println("PASS|SQLITE_INTEGRITY|ok");}}
  static void backup(Path db,Path target)throws Exception {if(!Files.isRegularFile(db))throw new IllegalArgumentException("数据库不存在: "+db);Files.createDirectories(target.getParent());if(Files.exists(target))throw new IllegalArgumentException("备份目标已存在，拒绝覆盖: "+target);try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement()){String safe=target.toString().replace("'","''");s.execute("VACUUM INTO '"+safe+"'");}check(target);System.out.println("PASS|SQLITE_BACKUP|"+target);}
  static void restore(Path db,Path source)throws Exception {if(!Files.isRegularFile(source))throw new IllegalArgumentException("备份文件不存在: "+source);check(source);Files.createDirectories(db.getParent());Path rollback=db.resolveSibling(db.getFileName()+".before-restore");if(Files.exists(db)){if(Files.exists(rollback))throw new IllegalStateException("回滚副本已存在，请先处理: "+rollback);Files.move(db,rollback);}Files.copy(source,db,StandardCopyOption.REPLACE_EXISTING);Files.deleteIfExists(db.resolveSibling(db.getFileName()+"-wal"));Files.deleteIfExists(db.resolveSibling(db.getFileName()+"-shm"));check(db);System.out.println("PASS|SQLITE_RESTORE|"+db+"|ROLLBACK_COPY|"+rollback);}
}
