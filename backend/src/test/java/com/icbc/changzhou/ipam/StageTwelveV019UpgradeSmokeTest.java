package com.icbc.changzhou.ipam;

import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.JsonObject;

/** Verifies the published V019-to-V020 upgrade against a real historical database copy. */
public final class StageTwelveV019UpgradeSmokeTest {
  public static void main(String[] args)throws Exception{
    if(args.length>1)throw new IllegalArgumentException("usage: StageTwelveV019UpgradeSmokeTest [v019-db]");
    Path work=Files.createTempDirectory("ipam-stage12-v019-upgrade-");
    Path source=args.length==0?work.resolve("v019-fixture.db"):Paths.get(args[0]).toAbsolutePath().normalize();
    if(args.length==0)createV019Fixture(source);
    if(!Files.isRegularFile(source))throw new AssertionError("V019 source database missing: "+source);
    Path db=work.resolve("ipam.db");copyDatabase(source,db);
    try(Connection c=open(db)){expect(c,"SELECT count(*) FROM schema_history WHERE version='019'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='020'",0);expect(c,"SELECT count(*) FROM ip_assignment",2);expect(c,"SELECT count(*) FROM ip_release_event",1);}
    migrate(db);
    try(Connection c=open(db)){expect(c,"SELECT count(*) FROM schema_history WHERE version='019'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='020'",1);expect(c,"SELECT count(*) FROM ip_assignment",2);expect(c,"SELECT count(*) FROM ip_release_event",1);try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA integrity_check")){if(!r.next()||!"ok".equalsIgnoreCase(r.getString(1)))throw new AssertionError("integrity_check failed");}try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new AssertionError("foreign_key_check failed: "+r.getString(1)+" row "+r.getLong(2));}}
    System.out.println("PASS|STAGE12_V019_EXISTING_ASSIGNMENT_RELEASE_UPGRADE|"+db);
  }
  /** Build the actual V019 schema without invoking the runtime V020 follow-up. */
  static void createV019Fixture(Path db)throws Exception {
    Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");Constructor<?> constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);Object runtime=constructor.newInstance(db);
    try(Connection c=open(db);Statement statement=c.createStatement()){
      statement.execute("CREATE TABLE schema_history(version TEXT PRIMARY KEY,description TEXT NOT NULL,checksum TEXT NOT NULL,installed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')))");
      List<Path> scripts=new ArrayList<>();try(java.util.stream.Stream<Path> files=Files.list(Paths.get("backend/src/main/resources/db/migration"))){files.filter(p->p.getFileName().toString().matches("V0(?:0[1-9]|1[0-8])__.*\\.sql")).sorted().forEach(scripts::add);}
      for(Path file:scripts){String name=file.getFileName().toString(),version=name.substring(1,4),method="apply";if(Arrays.asList("013","014").contains(version))method="applyIpApplicationRebuild";else if(Arrays.asList("015","016","017").contains(version))method="applySoftDeleteReuseRebuild";Method apply=type.getDeclaredMethod(method,Connection.class,String.class,String.class,String.class);apply.setAccessible(true);apply.invoke(runtime,c,version,"V019 historical fixture","/db/migration/"+name);}
      byte[] migration=StageTwelveV019UpgradeSmokeTest.class.getResourceAsStream("/db/migration/V019__password_policy_and_switch_port.sql").readAllBytes();
      for(String sql:new String(migration,StandardCharsets.UTF_8).split(";\\s*(?:\\r?\\n|$)"))if(!sql.trim().isEmpty())statement.execute(sql);
      StringBuilder checksum=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(migration))checksum.append(String.format("%02x",value));
      try(PreparedStatement record=c.prepareStatement("INSERT INTO schema_history(version,description,checksum) VALUES('019','password policy and switch port',?)")){record.setString(1,checksum.toString());record.executeUpdate();}
      statement.execute("INSERT INTO sys_user(id,username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES(1,'legacyadmin12','x','管理员','ADMIN',1,0,1)");
      statement.execute("INSERT INTO sys_user(id,username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES(2,'legacytech12','x','科技管理员','TECH_ADMIN',1,0,0)");
      statement.execute("INSERT INTO branch(id,code,name,is_active) VALUES(1,'B12','历史支行',1)");statement.execute("INSERT INTO outlet(id,branch_id,code,name,is_active) VALUES(1,1,'O12','历史网点',1)");
    }
    PhaseFourService four=new PhaseFourService(db);JsonObject plan=new JsonObject();plan.addProperty("branchId",1);plan.addProperty("outletId",1);plan.addProperty("productionCidr","108.56.128.0/27");four.createPlan(plan,1L);
    JsonObject body=new JsonObject();body.addProperty("assetType","TECH");body.addProperty("name","历史设备");body.addProperty("requiresUser",0);body.addProperty("requiresResourceCode",1);body.addProperty("recommendedRangeType","PRODUCTION");long asset=((Number)four.createAsset(body,1L).get("id")).longValue();
    try(Connection c=open(db);Statement s=c.createStatement()){
      for(int i=1;i<=2;i++)s.execute("INSERT INTO ip_application(id,applicant_user_id,applicant_name,applicant_username,unified_auth_id,branch_id,outlet_id,asset_id,asset_type,asset_name,requires_user,requires_resource_code,asset_code,recommended_range_type,final_range_type,status,reviewed_by) VALUES("+i+",2,'科技管理员','legacytech12','000012',1,1,"+asset+",'TECH','历史设备',0,1,'LEGACY-CODE-"+i+"','PRODUCTION','PRODUCTION','APPROVED',1)");
      s.execute("INSERT INTO ip_assignment(id,application_id,ip_pool_id,resource_code,assigned_by,status,switch_port) VALUES(1,1,1,'LEGACY-CODE-1',1,'ASSIGNED','108.56.195.250-36')");
      s.execute("INSERT INTO ip_assignment(id,application_id,ip_pool_id,resource_code,assigned_by,status,released_by,released_at,release_reason,switch_port) VALUES(2,2,2,'LEGACY-CODE-2',1,'RELEASED',2,'2026-09-16','历史释放','GE0/0/1')");
      s.execute("INSERT INTO ip_release_event(assignment_id,application_id,ip_address,resource_code,released_by,released_by_username,released_at,branch_name,outlet_name,asset_type,result,request_id,switch_port) VALUES(2,2,'108.56.128.2','LEGACY-CODE-2',2,'legacytech12','2026-09-16','历史支行','历史网点','TECH','RELEASED','legacy-release12','GE0/0/1')");
      expect(c,"SELECT count(*) FROM schema_history WHERE version='019'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='020'",0);
    }
  }
  private static void copyDatabase(Path source,Path target)throws Exception{try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+source);PreparedStatement p=c.prepareStatement("VACUUM INTO ?")){p.setString(1,target.toString());p.executeUpdate();}}
  private static void migrate(Path db)throws Exception{Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(Path.class);ctor.setAccessible(true);Object runtime=ctor.newInstance(db);java.lang.reflect.Method method=type.getDeclaredMethod("migrate");method.setAccessible(true);method.invoke(runtime);}
  private static Connection open(Path db)throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;}
  private static void expect(Connection c,String sql,long expected)throws Exception{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){if(!r.next())throw new AssertionError(sql+" returned no row");long actual=r.getLong(1);if(actual!=expected)throw new AssertionError(sql+" expected="+expected+" actual="+actual);}}
}
