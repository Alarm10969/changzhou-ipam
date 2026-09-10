package com.icbc.changzhou.ipam;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;

/** Exercises both V012 upgrade paths without deriving a historic PC user from applicant data. */
public final class StageSixLegacyUpgradeSmokeTest {
  private static final String OLD_V013_CHECKSUM="b54358d0baecd20c8cd9811c0efe58fc8d4e8dddd73515ca0ecce90b7be55415";
  private static final String[][] MIGRATIONS={{"001","stage1 foundation","/db/migration/V001__stage1_foundation.sql"},{"002","remove backup role and tech scope","/db/migration/V002__remove_backup_role_and_tech_scope.sql"},{"003","initial admin protection","/db/migration/V003__initial_admin_protection.sql"},{"004","bulk import and application workflow","/db/migration/V004__bulk_import_and_application_workflow.sql"},{"005","outlet range and default pool","/db/migration/V005__outlet_range_and_default_pool.sql"},{"006","organization soft delete and network defaults","/db/migration/V006__organization_soft_delete_and_network_defaults.sql"},{"007","immediate release notification statistics export","/db/migration/V007__immediate_release_notification_statistics_export.sql"},{"008","outlet directory information","/db/migration/V008__outlet_directory_information.sql"},{"009","remove redundant managed outlet number","/db/migration/V009__remove_redundant_managed_outlet_no.sql"},{"010","six network planning and asset mapping","/db/migration/V010__six_network_planning_and_asset_mapping.sql"},{"011","outlet plan batch import","/db/migration/V011__outlet_plan_batch_import.sql"},{"012","dynamic segment prefix and remove override reason","/db/migration/V012__dynamic_segment_prefix_and_remove_override_reason.sql"}};
  public static void main(String[] args)throws Exception{
    Path work=Files.createTempDirectory("ipam-stage6-"),legacy=work.resolve("legacy-v012.db"),oldV13=work.resolve("old-v013.db"),invalid=work.resolve("invalid-v012.db");
    Object runtime=runtime(legacy);applyV012(runtime,legacy);seed(legacy,false);migrate(runtime);
    try(Connection c=open(legacy)){
      expect(c,"SELECT count(*) FROM schema_history WHERE version='013'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='014'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='015'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='016'",1);
      row(c,"SELECT asset_code,user_name,user_unified_auth_id FROM ip_application WHERE asset_code='LEGACY-PC-1'","LEGACY-PC-1",null,null);
      expect(c,"SELECT count(*) FROM ip_application WHERE asset_code='OTHER-1'",1);expect(c,"SELECT count(*) FROM ip_application WHERE asset_type='NON_TECH'",1);foreignKeys(c);
    }
    migrate(runtime);try(Connection c=open(legacy)){expect(c,"SELECT count(*) FROM ip_application",3);expect(c,"SELECT count(*) FROM schema_history WHERE version='014'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='015'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='016'",1);foreignKeys(c);}

    Object previous=runtime(oldV13);applyV012(previous,oldV13);seed(oldV13,false);rebuild(previous,oldV13,"013","allow technology PC applicant identity","/db/migration/V013__allow_tech_pc_identity.sql");
    try(Connection c=open(oldV13);PreparedStatement p=c.prepareStatement("UPDATE schema_history SET checksum=? WHERE version='013'")){p.setString(1,OLD_V013_CHECKSUM);p.executeUpdate();}
    migrate(previous);try(Connection c=open(oldV13)){expect(c,"SELECT count(*) FROM schema_history WHERE version='014'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='015'",1);expect(c,"SELECT count(*) FROM schema_history WHERE version='016'",1);row(c,"SELECT asset_code,user_name,user_unified_auth_id FROM ip_application WHERE asset_code='LEGACY-PC-1'","LEGACY-PC-1",null,null);foreignKeys(c);}

    Object bad=runtime(invalid);applyV012(bad,invalid);seed(invalid,true);boolean rejected=false;try{migrate(bad);}catch(InvocationTargetException expected){rejected=true;}if(!rejected)throw new AssertionError("incomplete PC identity must not be silently accepted");
    try(Connection c=open(invalid)){expect(c,"SELECT count(*) FROM schema_history WHERE version='013'",0);expect(c,"SELECT count(*) FROM ip_application",1);}
    System.out.println("PASS|STAGE6_V012_V013_V014_LEGACY_UPGRADE|"+work);
  }
  private static Object runtime(Path db)throws Exception{Class<?> t=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");Constructor<?> c=t.getDeclaredConstructor(Path.class);c.setAccessible(true);return c.newInstance(db);}
  private static void applyV012(Object runtime,Path db)throws Exception{Method apply=runtime.getClass().getDeclaredMethod("apply",Connection.class,String.class,String.class,String.class);apply.setAccessible(true);try(Connection c=open(db);Statement s=c.createStatement()){s.execute("CREATE TABLE schema_history (version TEXT PRIMARY KEY, description TEXT NOT NULL, checksum TEXT NOT NULL, installed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))) ");for(String[] m:MIGRATIONS)apply.invoke(runtime,c,m[0],m[1],m[2]);}}
  private static void migrate(Object runtime)throws Exception{Method m=runtime.getClass().getDeclaredMethod("migrate");m.setAccessible(true);m.invoke(runtime);}
  private static void rebuild(Object runtime,Path db,String version,String description,String resource)throws Exception{Method m=runtime.getClass().getDeclaredMethod("applyIpApplicationRebuild",Connection.class,String.class,String.class,String.class);m.setAccessible(true);try(Connection c=open(db)){m.invoke(runtime,c,version,description,resource);}}
  private static void seed(Path db,boolean invalid)throws Exception{try(Connection c=open(db);Statement s=c.createStatement()){
    s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('legacy-admin','x','审批管理员','ADMIN',1,0,1)");
    s.execute("INSERT INTO branch(code,name,is_active) VALUES('LB','历史支行',1)");s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'LO','历史网点',1)");
    if(invalid){s.execute("INSERT INTO ip_application(applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,asset_code,device_type,status,user_name,user_unified_auth_id) VALUES(1,'申请人','A-1',1,1,'TECH','BROKEN-PC','PC','PENDING','不完整',NULL)");return;}
    s.execute("INSERT INTO ip_application(applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,asset_code,device_type,status) VALUES(1,'申请人','A-1',1,1,'TECH','LEGACY-PC-1','PC','PENDING')");
    s.execute("INSERT INTO ip_application(applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,asset_code,device_type,status) VALUES(1,'申请人','A-2',1,1,'TECH','OTHER-1','OTHER','PENDING')");
    s.execute("INSERT INTO ip_application(applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,status) VALUES(1,'申请人','A-3',1,1,'NON_TECH','PENDING')");
  }}
  private static Connection open(Path db)throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
  private static void expect(Connection c,String sql,long wanted)throws Exception{try(ResultSet r=c.createStatement().executeQuery(sql)){r.next();if(r.getLong(1)!=wanted)throw new AssertionError(sql+" expected="+wanted+" actual="+r.getLong(1));}}
  private static void row(Connection c,String sql,String a,String b,String d)throws Exception{try(ResultSet r=c.createStatement().executeQuery(sql)){if(!r.next()||!same(a,r.getString(1))||!same(b,r.getString(2))||!same(d,r.getString(3)))throw new AssertionError("legacy row changed or inferred");}}
  private static boolean same(String a,String b){return a==null?b==null:a.equals(b);}
  private static void foreignKeys(Connection c)throws Exception{try(ResultSet r=c.createStatement().executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new AssertionError("foreign-key check failed: "+r.getString(1));}}
}
