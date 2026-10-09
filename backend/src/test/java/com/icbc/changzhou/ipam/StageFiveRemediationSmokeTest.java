package com.icbc.changzhou.ipam;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Regression gate for the 2026-09-09 independent-acceptance remediation. */
public final class StageFiveRemediationSmokeTest {
  public static void main(String[] args) throws Exception {
    Path work=Files.createTempDirectory("ipam-stage5-"),db=work.resolve("remediation.db");
    Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");
    java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(Path.class);ctor.setAccessible(true);
    Object runtime=ctor.newInstance(db);java.lang.reflect.Method migrate=type.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(runtime);
    try(Connection c=open(db);Statement s=c.createStatement()){
      count(c,"SELECT count(*) FROM schema_history WHERE version='013'",1);
      count(c,"SELECT count(*) FROM schema_history WHERE version='014'",1);
      count(c,"SELECT count(*) FROM schema_history WHERE version='015'",1);
      count(c,"SELECT count(*) FROM schema_history WHERE version='016'",1);
      count(c,"SELECT count(*) FROM schema_history WHERE version='017'",1);
      count(c,"SELECT count(*) FROM schema_history WHERE version='018'",1);
      count(c,"SELECT count(*) FROM pragma_table_info('ip_application') WHERE name='override_reason'",1);
      s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('admin5','"+org.mindrot.jbcrypt.BCrypt.hashpw("Stage5Demo9",org.mindrot.jbcrypt.BCrypt.gensalt(4))+"','管理员','ADMIN',1,0,1)");
      s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('tech5','x','科技管理员','TECH_ADMIN',1,0,0)");
      s.execute("INSERT INTO branch(code,name,is_active) VALUES('B5','回归支行',1)");
      s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'O5','回归网点',1)");
    }
    PhaseFourService service=new PhaseFourService(db);
    JsonObject plan=new JsonObject();plan.addProperty("branchId",1);plan.addProperty("outletId",1);plan.addProperty("productionCidr","108.56.0.0/24");service.createPlan(plan,1L);
    JsonObject asset=new JsonObject();asset.addProperty("assetType","TECH");asset.addProperty("name","双关联回归资产");asset.addProperty("requiresUser",1);asset.addProperty("requiresResourceCode",1);asset.addProperty("recommendedRangeType","OFFICE");asset.addProperty("active",1);long assetId=((Number)service.createAsset(asset,1L).get("id")).longValue();
    JsonObject pc=new JsonObject();pc.addProperty("branchId",1);pc.addProperty("outletId",1);pc.addProperty("assetId",assetId);pc.addProperty("userName","张三");pc.addProperty("userUnifiedAuthId","U00995538");boolean missingCode=false;try{service.submitApplication(2L,pc);}catch(PhaseTwoService.Problem e){missingCode="ASSET_CODE_REQUIRED".equals(e.code);}if(!missingCode)throw new AssertionError("asset resource-code requirement must be enforced");pc.addProperty("assetCode","PC-CZ-009");
    long app=service.submitApplication(2L,pc);
    Map<String,Object> candidates=service.candidates(app,"OFFICE","",1);
    long pool=((Number)((Map<?,?>)((List<?>)candidates.get("items")).get(0)).get("id")).longValue();
    service.approve(app,pool,"OFFICE","PC approval",1L);
    pc.addProperty("assetCode","PC-CZ-010");
    long crossApp=service.submitApplication(2L,pc);
    Map<String,Object> crossCandidates=service.candidates(crossApp,"TEST","",1);
    long crossPool=((Number)((Map<?,?>)((List<?>)crossCandidates.get("items")).get(0)).get("id")).longValue();
    boolean missingReason=false;try{service.approve(crossApp,crossPool,"TEST","cross approval",1L);}catch(PhaseTwoService.Problem e){missingReason="OVERRIDE_REASON_REQUIRED".equals(e.code);}if(!missingReason)throw new AssertionError("cross-range approval must require an override reason");
    service.approve(crossApp,crossPool,"TEST","cross approval","业务隔离需要测试网段",1L);
    Map<String,Object> crossApproved=find(service.applications("APPROVED",""),crossApp);
    if(!"业务隔离需要测试网段".equals(crossApproved.get("overrideReason")))throw new AssertionError("cross-range reason must be retained in application history");
    String exported=new String(new PhaseThreeService(db).export("APPLICATION","CSV",new HashSet<String>(Arrays.asList("overrideReason")),Collections.<String,String>emptyMap(),1L),java.nio.charset.StandardCharsets.UTF_8);
    if(!exported.contains("业务隔离需要测试网段"))throw new AssertionError("export must contain selected override reason");
    try(Connection c=open(db);PreparedStatement p=c.prepareStatement("SELECT count(*) FROM audit_log WHERE action='APPROVE_APPLICATION_V3' AND detail_json LIKE '%业务隔离需要测试网段%'")){try(ResultSet r=p.executeQuery()){r.next();if(r.getLong(1)!=1)throw new AssertionError("cross-range approval audit missing reason");}}
    Map<String,Object> active=find(service.applications("APPROVED",""),app);
    String ip=String.valueOf(active.get("finalIpAddress"));
    if(active.get("finalIpAddress")==null||!"ASSIGNED".equals(active.get("assignmentStatus")))throw new AssertionError("approved PC must retain active final IP");
    long assignment;try(Connection c=open(db);PreparedStatement p=c.prepareStatement("SELECT id FROM ip_assignment WHERE application_id=?")){p.setLong(1,app);try(ResultSet r=p.executeQuery()){r.next();assignment=r.getLong(1);}}
    new PhaseThreeService(db).release(assignment,2L,"stage five release",UUID.randomUUID().toString());
    Map<String,Object> released=find(service.applications("APPROVED",""),app);
    if(!ip.equals(released.get("finalIpAddress"))||!"RELEASED".equals(released.get("assignmentStatus"))||released.get("releasedAt")==null)throw new AssertionError("released application must retain its actual historical IP");
    try(Connection c=open(db)){count(c,"SELECT count(*) FROM user_notification WHERE application_id="+app,1);}
    try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new AssertionError("foreign key check failed after V014: "+r.getString(1));}
    String js=Files.readString(Paths.get("frontend","app.js"));
    String css=Files.readString(Paths.get("frontend","styles.css"));
    if(!js.contains("function poolV3")||!js.contains("分配对象")||!js.contains("function editState(item, page)")||!js.contains("item.state === 'MANUAL_RESERVED' ? ' selected' : ''")||!js.contains("item.state === 'DISABLED' ? ' selected' : ''")||!js.contains("function editInformation(item, page)")||!js.contains("'/information', body")||!js.contains("expectedVersion: Number(item.assignmentVersion)")||!js.contains("S.v === 'pool') poolV3()")||!js.contains("function readOnlyDialog")||!js.contains("function applicationsV4")||!js.contains("function settingsV4")||!js.contains("S.v === 'settings') settingsV4()")||!js.contains("function applyV3")||!js.contains("关联资源编码")||!js.contains("id=\"candidateGo\"")||!js.contains("'确认分配'")||!js.contains("id=\"assetNotice\"")||!css.contains(".grid>.approval-workflow{width:100%;min-width:0}"))throw new AssertionError("active frontend remediation bindings missing");
    System.out.println("PASS|STAGE5_V017_ASSET_HISTORY_UI_REMEDIATION|"+work);
  }
  private static Map<String,Object> find(List<Map<String,Object>> rows,long id){for(Map<String,Object> row:rows)if(((Number)row.get("id")).longValue()==id)return row;throw new AssertionError("application not returned: "+id);}
  private static Connection open(Path db)throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
  private static void count(Connection c,String sql,long wanted)throws Exception{try(ResultSet r=c.createStatement().executeQuery(sql)){r.next();if(r.getLong(1)!=wanted)throw new AssertionError(sql+" expected="+wanted+" actual="+r.getLong(1));}}
}
