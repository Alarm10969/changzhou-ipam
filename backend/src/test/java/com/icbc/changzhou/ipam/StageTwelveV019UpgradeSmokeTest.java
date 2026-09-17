package com.icbc.changzhou.ipam;

import java.nio.file.*;
import java.sql.*;

/** Verifies the published V019-to-V020 upgrade against a real historical database copy. */
public final class StageTwelveV019UpgradeSmokeTest {
  public static void main(String[] args)throws Exception{
    if(args.length!=1)throw new IllegalArgumentException("usage: StageTwelveV019UpgradeSmokeTest <v019-db>");
    Path source=Paths.get(args[0]).toAbsolutePath().normalize();if(!Files.isRegularFile(source))throw new AssertionError("V019 source database missing: "+source);
    Path work=Files.createTempDirectory("ipam-stage12-v019-upgrade-");Path db=work.resolve("ipam.db");copyDatabase(source,db);
    try(Connection c=open(db)){expect(c,"SELECT count(*) FROM schema_history WHERE version='019'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='020'",0);expect(c,"SELECT count(*) FROM ip_assignment",2);expect(c,"SELECT count(*) FROM ip_release_event",1);}
    migrate(db);
    try(Connection c=open(db)){expect(c,"SELECT count(*) FROM schema_history WHERE version='019'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='020'",1);expect(c,"SELECT count(*) FROM ip_assignment",2);expect(c,"SELECT count(*) FROM ip_release_event",1);try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA integrity_check")){if(!r.next()||!"ok".equalsIgnoreCase(r.getString(1)))throw new AssertionError("integrity_check failed");}try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new AssertionError("foreign_key_check failed: "+r.getString(1)+" row "+r.getLong(2));}}
    System.out.println("PASS|STAGE12_V019_EXISTING_ASSIGNMENT_RELEASE_UPGRADE|"+db);
  }
  private static void copyDatabase(Path source,Path target)throws Exception{try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+source);PreparedStatement p=c.prepareStatement("VACUUM INTO ?")){p.setString(1,target.toString());p.executeUpdate();}}
  private static void migrate(Path db)throws Exception{Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(Path.class);ctor.setAccessible(true);Object runtime=ctor.newInstance(db);java.lang.reflect.Method method=type.getDeclaredMethod("migrate");method.setAccessible(true);method.invoke(runtime);}
  private static Connection open(Path db)throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;}
  private static void expect(Connection c,String sql,long expected)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){if(!r.next())throw new AssertionError(sql+" returned no row");long actual=r.getLong(1);if(actual!=expected)throw new AssertionError(sql+" expected="+expected+" actual="+actual);}}
}
