package com.icbc.changzhou.ipam;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class StageEighteenBranchSetupSmokeTest {
  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("ipam-stage18-"),file=work.resolve("ipam.db");
    Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");
    Constructor<?> constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
    Object db=constructor.newInstance(file);Method migrate=type.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(db);
    PhaseFourService four=new PhaseFourService(file);SetupService setup=new SetupService(file);
    check(!Boolean.TRUE.equals(setup.status().get("ready")),"fresh database must not be ready");
    check(((Number)setup.status().get("branchCount")).longValue()==0,"clean install contains no business branches");
    sql(file,"INSERT INTO sys_user(username,password_hash,real_name,role,is_initial_admin) VALUES('admin18','x','管理员','ADMIN',1)");
    expect("SETUP_NOT_READY",()->setup.complete(1));
    JsonObject rules=new Gson().toJsonTree(four.settings()).getAsJsonObject();
    for(JsonElement segment:rules.getAsJsonArray("segments"))segment.getAsJsonObject().addProperty("enabled",true);
    rules.addProperty("globalCidr","10.0.0.0/8");rules.addProperty("changeReason","分行实际规划");
    JsonObject tooSmall=rules.deepCopy();tooSmall.addProperty("globalCidr","10.0.0.0/24");expect("GLOBAL_RANGE_TOO_SMALL",()->four.saveRule(tooSmall,1));
    four.saveRule(rules,1);
    check(Boolean.TRUE.equals(setup.status().get("rulesReviewed")),"saved rule must be reviewed");
    sql(file,"INSERT INTO branch(code,name) VALUES('B18','测试分行')");
    sql(file,"INSERT INTO outlet(branch_id,code,name) VALUES(1,'O18','测试网点'),(1,'O19','测试网点二')");
    JsonObject plan=json("branchId",1,"outletId",1,"productionCidr","10.10.0.0/27");four.previewPlan(plan);four.createPlan(plan,1);
    JsonObject oldRange=json("branchId",1,"outletId",2,"productionCidr","108.56.64.0/27");
    expect("CIDR_OUT_OF_GLOBAL_RANGE",()->four.previewPlan(oldRange));
    JsonObject shrink=rules.deepCopy();shrink.addProperty("globalCidr","192.168.0.0/16");
    expect("GLOBAL_RANGE_EXCLUDES_EXISTING",()->four.saveRule(shrink,1));
    check("10.0.0.0/8".equals(four.settings().get("globalCidr")),"rejected range must not change active rule");
    JsonObject invalid=rules.deepCopy();invalid.addProperty("globalCidr","10.0.0.1/8");expect("CIDR_INVALID",()->four.saveRule(invalid,1));
    JsonObject expanded=rules.deepCopy();expanded.addProperty("globalCidr","0.0.0.0/0");four.saveRule(expanded,1);
    check(((Number)setup.status().get("plannedOutletCount")).longValue()==1,"actual planned outlet count");
    expect("SETUP_NOT_READY",()->setup.complete(1));
    four.createAsset(json("assetType","TECH","name","分行测试设备","requiresUser",0,"requiresResourceCode",0,"recommendedRangeType","OFFICE"),1);
    sql(file,"INSERT INTO sys_user(username,password_hash,real_name,role) VALUES('tech18','x','科技管理员','TECH_ADMIN')");
    check(Boolean.TRUE.equals(setup.status().get("ready")),"complete prerequisites must be ready");
    setup.complete(1);
    check(!String.valueOf(new SetupService(file).status().get("completedAt")).isEmpty(),"completion must survive service recreation");
    sql(file,"UPDATE sys_user SET is_active=0 WHERE username='tech18'");
    expect("SETUP_NOT_READY",()->setup.complete(1));
    check(!Boolean.TRUE.equals(setup.status().get("ready")),"old completion must not conceal disabled account");
    sql(file,"UPDATE sys_user SET is_active=1 WHERE username='tech18'");
    sql(file,"UPDATE branch SET is_active=0 WHERE id=1");
    check(((Number)setup.status().get("plannedOutletCount")).longValue()==0,"disabled organization must not qualify");
    System.out.println("PASS|STAGE18_BRANCH_SETUP|clean,custom-range,derive,old-range-rejected,preserve,expand,readiness,recheck,persistence");
  }
  static JsonObject json(Object...v){JsonObject b=new JsonObject();for(int i=0;i<v.length;i+=2){if(v[i+1] instanceof Number)b.addProperty(String.valueOf(v[i]),(Number)v[i+1]);else b.addProperty(String.valueOf(v[i]),String.valueOf(v[i+1]));}return b;}
  static void sql(Path f,String sql)throws Exception{try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+f);Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute(sql);}}
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
  interface Operation {void run()throws Exception;}
  static void expect(String code,Operation op)throws Exception{try{op.run();throw new AssertionError("missing "+code);}catch(PhaseTwoService.Problem e){check(code.equals(e.code),"expected "+code+" got "+e.code);}}
}
