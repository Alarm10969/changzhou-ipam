package com.icbc.changzhou.ipam;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** No-framework stage-three regression.  Runs against a real V006-to-V007 SQLite upgrade path. */
public final class StageThreeSmokeTest {
  public static void main(String[] args) throws Exception {
    Path work=Files.createTempDirectory("ipam-stage3-");Path db=work.resolve("v006-upgrade.db");
    try(Connection c=open(db)){
      apply(c,"V001__stage1_foundation.sql");apply(c,"V002__remove_backup_role_and_tech_scope.sql");
      c.createStatement().execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password) VALUES('admin','x','管理员','ADMIN',1,0)");
      apply(c,"V003__initial_admin_protection.sql");apply(c,"V004__bulk_import_and_application_workflow.sql");apply(c,"V005__outlet_range_and_default_pool.sql");apply(c,"V006__organization_soft_delete_and_network_defaults.sql");
      c.createStatement().execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password) VALUES('tech','x','科技管理员','TECH_ADMIN',1,0)");
      c.createStatement().execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password) VALUES('disabled','x','停用管理员','ADMIN',0,0)");
      c.createStatement().execute("INSERT INTO branch(code,name) VALUES('B1','测试支行')");c.createStatement().execute("INSERT INTO outlet(branch_id,code,name) VALUES(1,'O1','测试网点')");
      c.createStatement().execute("INSERT INTO ip_range(branch_id,outlet_id,cidr,subnet_mask,gateway,dns_primary) VALUES(1,1,'10.10.0.0/24','255.255.255.0','10.10.0.1','8.8.8.8')");
      c.createStatement().execute("INSERT INTO ip_address_pool(ip_range_id,ip_address) VALUES(1,'10.10.0.8')");
      c.createStatement().execute("INSERT INTO ip_application(applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,asset_code,device_type,status) VALUES(2,'科技管理员','900000001',1,1,'TECH','ASSET-1','PC','APPROVED')");
      c.createStatement().execute("INSERT INTO ip_assignment(application_id,ip_pool_id,resource_code,assigned_by) VALUES(1,1,'ASSET-1',1)");
      apply(c,"V007__immediate_release_notification_statistics_export.sql");
      try(ResultSet r=c.createStatement().executeQuery("SELECT status FROM ip_assignment WHERE id=1")){if(!r.next()||!"ASSIGNED".equals(r.getString(1)))throw new AssertionError("V006 assignment must become ASSIGNED");}
    }
    PhaseThreeService phase3=new PhaseThreeService(db);Map<String,Object> released=phase3.release(1L,2L,"设备下线","stage3-request-1");if(!"RELEASED".equals(released.get("status")))throw new AssertionError("release did not return RELEASED");
    try(Connection c=open(db)){assertCount(c,"SELECT count(*) FROM ip_release_event",1);assertCount(c,"SELECT count(*) FROM user_notification WHERE release_event_id IS NOT NULL AND user_id=1",1);assertCount(c,"SELECT count(*) FROM user_notification WHERE release_event_id IS NOT NULL AND user_id=3",0);assertCount(c,"SELECT count(*) FROM audit_log WHERE action='RELEASE_IP'",1);}
    boolean repeat=false;try{phase3.release(1L,2L,"again","stage3-request-2");}catch(PhaseTwoService.Problem e){repeat="IP_ALREADY_RELEASED".equals(e.code);}if(!repeat)throw new AssertionError("repeat release must be rejected");
    boolean forbidden=false;try{phase3.release(1L,1L,"bad","stage3-request-3");}catch(PhaseTwoService.Problem e){forbidden="RELEASE_FORBIDDEN".equals(e.code);}if(!forbidden)throw new AssertionError("non-owner must not release");
    PhaseTwoService phase2=new PhaseTwoService(db);try(Connection c=open(db)){c.createStatement().execute("INSERT INTO ip_application(applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,asset_code,device_type,status) VALUES(2,'科技管理员','900000002',1,1,'TECH','ASSET-1','PC','PENDING')");}if(phase2.availableIps(2L,"10.10.0.8",1).size()!=1)throw new AssertionError("released IP must become available");phase2.approve(2L,1L,1L);
    Map<String,Object> overview=phase3.overview(null,null);if(((Number)overview.get("assignedCount")).longValue()!=1L||((Number)overview.get("releasedTotal")).longValue()!=1L)throw new AssertionError("overview totals do not match data");
    byte[] csv=phase3.export("RELEASE","CSV",new LinkedHashSet<String>(),new LinkedHashMap<String,String>(),1L);if(csv.length<3||csv[0]!=(byte)0xef||csv[1]!=(byte)0xbb||csv[2]!=(byte)0xbf)throw new AssertionError("CSV BOM missing");
    byte[] xlsx=phase3.export("RELEASE","XLSX",new LinkedHashSet<String>(),new LinkedHashMap<String,String>(),1L);if(xlsx.length<4||xlsx[0]!='P'||xlsx[1]!='K')throw new AssertionError("XLSX output invalid");
    System.out.println("PASS|STAGE3_V007_IMMEDIATE_RELEASE_NOTIFY_REUSE_STATS_EXPORT|"+work);
  }
  private static Connection open(Path db)throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
  private static void apply(Connection c,String name)throws Exception{String resource="/db/migration/"+name;String sql=new String(StageThreeSmokeTest.class.getResourceAsStream(resource).readAllBytes(),StandardCharsets.UTF_8);try(Statement s=c.createStatement()){for(String part:sql.split(";\\s*(?:\\r?\\n|$)"))if(!part.trim().isEmpty())s.execute(part);}}
  private static void assertCount(Connection c,String sql,long expected)throws Exception{try(ResultSet r=c.createStatement().executeQuery(sql)){r.next();if(r.getLong(1)!=expected)throw new AssertionError(sql+" expected "+expected+" actual "+r.getLong(1));}}
}
