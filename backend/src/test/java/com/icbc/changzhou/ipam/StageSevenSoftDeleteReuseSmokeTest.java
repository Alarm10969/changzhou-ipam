package com.icbc.changzhou.ipam;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.sql.*;

/** Regression gate: a deleted outlet can be recreated and planned with the same CIDRs. */
public final class StageSevenSoftDeleteReuseSmokeTest {
  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("ipam-stage7-"),db=work.resolve("reuse.db");
    Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");
    java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(Path.class);ctor.setAccessible(true);
    Object runtime=ctor.newInstance(db);java.lang.reflect.Method migrate=type.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(runtime);
    try(Connection c=open(db);Statement s=c.createStatement()){
      count(c,"SELECT count(*) FROM schema_history WHERE version='015'",1);
      s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('admin7','x','管理员', 'ADMIN',1,0,1)");
      s.execute("INSERT INTO branch(code,name,is_active) VALUES('B7','重建测试支行',1)");
      s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'REUSE','原网点',1)");
    }
    PhaseFourService planner=new PhaseFourService(db);JsonObject plan=plan(1);planner.createPlan(plan,1L);
    new PhaseTwoService(db).deleteOutlet(1L,1L);
    try(Connection c=open(db);Statement s=c.createStatement()){
      count(c,"SELECT count(*) FROM outlet WHERE id=1 AND deleted_at IS NOT NULL",1);
      count(c,"SELECT count(*) FROM ip_range WHERE outlet_id=1 AND deleted_at IS NOT NULL",6);
      count(c,"SELECT count(*) FROM ip_address_pool WHERE deleted_at IS NOT NULL",1524);
      s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'REUSE','重建后的网点',1)");
    }
    planner.createPlan(plan(2),1L);
    try(Connection c=open(db);Statement s=c.createStatement()){
      count(c,"SELECT count(*) FROM outlet WHERE branch_id=1 AND code='REUSE' AND deleted_at IS NULL",1);
      count(c,"SELECT count(*) FROM ip_range WHERE outlet_id=2 AND deleted_at IS NULL",6);
      count(c,"SELECT count(*) FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id WHERE r.outlet_id=2 AND p.deleted_at IS NULL",1524);
      boolean duplicateRejected=false;try{s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'REUSE','重复网点',1)");}catch(SQLException expected){duplicateRejected=true;}if(!duplicateRejected)throw new AssertionError("visible duplicate outlet code must remain blocked");
      try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new AssertionError("foreign-key check failed after reuse: "+r.getString(1));}
    }
    String app=Files.readString(Paths.get("backend","src","main","java","com","icbc","changzhou","ipam","IpamApplication.java"));
    if(!app.contains("OUTLET_CODE_DUPLICATE"))throw new AssertionError("active duplicate must return a business error");
    System.out.println("PASS|STAGE7_SOFT_DELETED_OUTLET_RANGE_POOL_REUSE|"+work);
  }
  private static JsonObject plan(long outlet){JsonObject x=new JsonObject();x.addProperty("branchId",1);x.addProperty("outletId",outlet);x.addProperty("productionCidr","108.56.0.0/24");return x;}
  private static Connection open(Path db)throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
  private static void count(Connection c,String sql,long expected)throws Exception{try(ResultSet r=c.createStatement().executeQuery(sql)){r.next();if(r.getLong(1)!=expected)throw new AssertionError(sql+" expected="+expected+" actual="+r.getLong(1));}}
}
