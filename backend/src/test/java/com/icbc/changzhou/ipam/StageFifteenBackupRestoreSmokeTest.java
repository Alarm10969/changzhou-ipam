package com.icbc.changzhou.ipam;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.mindrot.jbcrypt.BCrypt;

/** Uses isolated databases only: complete snapshots, validation and crash recovery. */
public final class StageFifteenBackupRestoreSmokeTest {
  private static final Gson JSON=new Gson();
  private static final String[] MIGRATIONS={"stage1_foundation","remove_backup_role_and_tech_scope","initial_admin_protection","bulk_import_and_application_workflow","outlet_range_and_default_pool","organization_soft_delete_and_network_defaults","immediate_release_notification_statistics_export","outlet_directory_information","remove_redundant_managed_outlet_no","six_network_planning_and_asset_mapping","outlet_plan_batch_import","dynamic_segment_prefix_and_remove_override_reason","allow_tech_pc_identity","preserve_legacy_pc_identity","allow_soft_deleted_network_reuse","allow_soft_deleted_branch_and_user_reuse","reset_demo_business_data_and_simplify_assets","restore_override_reason_for_cross_range_approval","password_policy_and_switch_port","manual_assignment_and_gateway","mac_address"};
  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("ipam-stage15-backup-");Path db=work.resolve("ipam.db");
    migrate(db);seed(db);MaintenanceGate gate=new MaintenanceGate();
    try(BackupService service=new BackupService(db,gate,StageFifteenBackupRestoreSmokeTest::migrate)) {
      check(Boolean.TRUE.equals(service.status().get("reminderDue")),"first backup reminder missing");
      Path archive=work.resolve("saved.zip");
      try(BackupService.BackupFile file=service.download(2,"other15")) {
        Files.copy(file.path,archive);check(file.fileName.endsWith(".zip"),"backup filename");
      }
      check(!Boolean.TRUE.equals(service.status().get("reminderDue")),"fresh backup reminder");
      check("other15".equals(service.status().get("lastBackupBy")),"global backup operator");
      Path metadata=work.resolve("backup-management/last-backup.json");
      Files.writeString(metadata,JSON.toJson(map("lastBackupAt",Instant.now().minusSeconds(8*86400).toString(),"lastBackupBy","other15")));
      check(Boolean.TRUE.equals(service.status().get("reminderDue")),"seven day reminder");
      Map<String,Object> preview=preview(service,archive,2);
      check(((Number)preview.get("sourceSchema")).intValue()==22,"current backup schema");
      Map<?,?> counts=(Map<?,?>)preview.get("counts");
      check(((Number)counts.get("sys_user")).longValue()==3,"accounts omitted from backup");
      check(((Number)counts.get("ip_assignment")).longValue()==2,"assignments omitted from backup");
      change(db,"UPDATE branch SET name='after backup' WHERE id=1");
      String jobId;
      try(MaintenanceGate.Permit currentRequest=gate.enterApi()) {
        jobId=String.valueOf(service.confirm(String.valueOf(preview.get("uploadId")),2,"other15").get("jobId"));
        long end=System.currentTimeMillis()+5000;
        while(!gate.isMaintaining()&&System.currentTimeMillis()<end)Thread.sleep(10);
        check(gate.isMaintaining(),"restore did not enter maintenance");
        check(gate.enterApi()==null,"new API allowed during maintenance");
        check("after backup".equals(text(db,"SELECT name FROM branch WHERE id=1")),"database changed before API requests drained");
        check(!"SUCCEEDED".equals(service.jobStatus(jobId).get("status")),"restore passed active request");
      }
      check("SUCCEEDED".equals(await(service,jobId).get("status")),"complete restore failed");
      try(MaintenanceGate.Permit finishing=gate.enterMaintenance()) {
        Map<String,Object> pending=service.jobStatus(jobId);
        check("RESTORING".equals(pending.get("status")),"restore announced success before maintenance ended");
        check(Boolean.FALSE.equals(pending.get("requiresRelogin")),"finishing restore requested early login");
      }
      check("SUCCEEDED".equals(service.jobStatus(jobId).get("status")),"finished restore success missing after gate reopened");
      check("original branch".equals(text(db,"SELECT name FROM branch WHERE id=1")),"full restore did not replace business data");
      check(count(db,"SELECT count(*) FROM sys_session WHERE revoked_at IS NULL")==0,"backup sessions revived");
      check(count(db,"SELECT count(*) FROM bulk_import_job WHERE status='EXPIRED'")==1,"restored bulk preview remained confirmable");
      check(count(db,"SELECT count(*) FROM outlet_plan_import_job WHERE status='EXPIRED'")==1,"restored planning preview remained confirmable");
      check(count(db,"SELECT count(*) FROM bulk_import_job WHERE status='COMPLETED'")==1,"restore invalidated completed import history");
      check(count(db,"SELECT count(*) FROM sys_user")==3,"restore lost account data");
      check("AA:BB:CC:DD:EE:FF".equals(text(db,"SELECT mac_address FROM ip_assignment WHERE id=1")),"MAC snapshot lost");
      check("SN15".equals(text(db,"SELECT serial_number FROM ip_assignment WHERE id=1")),"serial snapshot lost");
      check("GE0/0/1".equals(text(db,"SELECT switch_port FROM ip_assignment WHERE id=1")),"switch port lost");
      check("10.8.0.8".equals(text(db,"SELECT switch_ip FROM ip_assignment WHERE id=1")),"switch address lost");
      check(count(db,"SELECT count(*) FROM ip_address_pool WHERE is_occupied=1")==1,"occupied status lost");
      check(Boolean.TRUE.equals(service.jobStatus(jobId).get("requiresRelogin")),"restore login result");
      expectProblem(() -> service.confirm(String.valueOf(preview.get("uploadId")),2,"other15"),"RESTORE_PREVIEW_EXPIRED");
      Path rollback=work.resolve("backup-management/before-restore-"+jobId+".db");
      check("after backup".equals(text(rollback,"SELECT name FROM branch WHERE id=1")),"automatic pre-restore backup missing or stale");
      expectSqlFailure(db,"DELETE FROM sys_user WHERE is_initial_admin=1");
      expectSqlFailure(db,"UPDATE sys_user SET is_active=0 WHERE is_initial_admin=1");
      check(count(db,"SELECT count(*) FROM audit_log WHERE action='RESTORE_BACKUP'")==1,"restore audit missing");
      assertInvalidUploads(service,db,work,archive);
      Map<String,Object> canceled=preview(service,archive,2);
      service.cancel(String.valueOf(canceled.get("uploadId")),2);service.cancel(String.valueOf(canceled.get("uploadId")),2);
      expectProblem(() -> service.confirm(String.valueOf(canceled.get("uploadId")),2,"other15"),"RESTORE_PREVIEW_EXPIRED");
      assertChangedPreviewRejected(service,gate,db,work,archive);
      assertLegacyUpgrade(service,work);
    }
    assertCrashRecovery(work,db);
    System.out.println("PASS|STAGE15_BACKUP_RESTORE|snapshots,reminders,validation,maintenance,sessions,legacy-upgrade,rollback");
  }

  private static void assertInvalidUploads(BackupService service,Path db,Path work,Path archive)throws Exception {
    String before=text(db,"SELECT name FROM branch WHERE id=1");
    Path invalid=work.resolve("invalid.db");Files.writeString(invalid,"not a database");
    expectProblem(() -> preview(service,invalid,2),null);
    Path paths=work.resolve("traversal.zip");
    try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(paths))){out.putNextEntry(new ZipEntry("../escaped.db"));out.write(new byte[]{1,2,3});out.closeEntry();}
    expectProblem(() -> preview(service,paths,2),"BACKUP_ARCHIVE_INVALID");check(!Files.exists(work.resolve("escaped.db")),"ZIP escaped upload directory");
    Path future=work.resolve("future.db");copySnapshot(db,future);change(future,"INSERT INTO schema_history(version,description,checksum) VALUES('023','future','invalid')");
    expectProblem(() -> preview(service,future,2),"BACKUP_VERSION_UNSUPPORTED");
    Path altered=work.resolve("altered.db");copySnapshot(db,altered);change(altered,"DROP TRIGGER trg_initial_admin_no_delete");
    expectProblem(() -> preview(service,altered,2),"BACKUP_SCHEMA_INVALID");
    Path associations=work.resolve("broken-links.db");copySnapshot(db,associations);
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+associations);Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=OFF");s.execute("UPDATE ip_assignment SET ip_pool_id=999999 WHERE id=1");}
    expectProblem(() -> preview(service,associations,2),"BACKUP_FOREIGN_KEY_INVALID");
    Path corruptManifest=work.resolve("corrupt-manifest.zip");
    try(ZipInputStream in=new ZipInputStream(Files.newInputStream(archive));ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(corruptManifest))) {
      ZipEntry e;while((e=in.getNextEntry())!=null){out.putNextEntry(new ZipEntry(e.getName()));byte[] contents=in.readAllBytes();
        if("manifest.json".equals(e.getName())){JsonObject manifest=JsonParser.parseString(new String(contents,StandardCharsets.UTF_8)).getAsJsonObject();manifest.addProperty("databaseSha256","wrong");contents=JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);}
        out.write(contents);out.closeEntry();in.closeEntry();}
    }
    expectProblem(() -> preview(service,corruptManifest,2),"BACKUP_MANIFEST_INVALID");
    Map<String,Object> owned=preview(service,archive,2);
    expectProblem(() -> service.confirm(String.valueOf(owned.get("uploadId")),1,"admin15"),"RESTORE_PREVIEW_EXPIRED");
    expectProblem(() -> service.cancel(String.valueOf(owned.get("uploadId")),1),"RESTORE_PREVIEW_FORBIDDEN");
    service.cancel(String.valueOf(owned.get("uploadId")),2);
    check(before.equals(text(db,"SELECT name FROM branch WHERE id=1")),"preview validation modified live database");
  }
  private static void assertChangedPreviewRejected(BackupService service,MaintenanceGate gate,Path db,Path work,Path archive)throws Exception {
    Map<String,Object> p=preview(service,archive,2);Path staged;
    try(java.util.stream.Stream<Path> entries=Files.list(work.resolve("backup-management"))) {
      staged=entries.filter(x->x.getFileName().toString().startsWith("preview-")).findFirst().orElseThrow().resolve("candidate.db");
    }
    change(staged,"UPDATE branch SET name='tampered preview' WHERE id=1");
    String before=text(db,"SELECT name FROM branch WHERE id=1"),job=String.valueOf(service.confirm(String.valueOf(p.get("uploadId")),2,"other15").get("jobId"));
    check("FAILED".equals(await(service,job).get("status")),"changed preview was restored");
    long deadline=System.currentTimeMillis()+5000;
    while(gate.isMaintaining()&&System.currentTimeMillis()<deadline)Thread.sleep(10);
    check(!gate.isMaintaining(),"failed preview restore did not leave maintenance");
    try(MaintenanceGate.Permit finishing=gate.enterMaintenance()) {
      check("FAILED".equals(service.jobStatus(job).get("status")),"maintenance hid restore failure");
    }
    check(before.equals(text(db,"SELECT name FROM branch WHERE id=1")),"failed restore changed original database");
  }
  private static void assertLegacyUpgrade(BackupService service,Path work)throws Exception {
    Path legacy=work.resolve("legacy-v021.db");migrateLegacy(legacy);seedLegacy(legacy);
    String digest=sha(legacy);Map<String,Object> p=preview(service,legacy,2);
    check(((Number)p.get("sourceSchema")).intValue()==21&&((Number)p.get("targetSchema")).intValue()==22,"V021 restore preview did not upgrade isolated copy");
    check(digest.equals(sha(legacy)),"legacy backup source was modified");
    check(count(legacy,"SELECT count(*) FROM schema_history WHERE version='022'")==0,"legacy source migrated in place");
    service.cancel(String.valueOf(p.get("uploadId")),2);
  }
  private static void assertCrashRecovery(Path work,Path db)throws Exception {
    Path root=work.resolve("backup-management");String job="11223344556677889900aabbccddeeff";
    Path rollback=root.resolve("before-restore-"+job+".db");copySnapshot(db,rollback);
    String original=text(db,"SELECT name FROM branch WHERE id=1");
    change(db,"UPDATE branch SET name='interrupted replacement' WHERE id=1");
    Files.writeString(root.resolve("restore-marker.json"),JSON.toJson(map("jobId",job,"phase","SWAPPED","rollbackName",rollback.getFileName().toString(),"rollbackSha256",sha(rollback))));
    BackupService.recoverInterrupted(db);
    check(original.equals(text(db,"SELECT name FROM branch WHERE id=1")),"interrupted restore did not roll back");
    check(!Files.exists(root.resolve("restore-marker.json")),"successful recovery left marker");
    check("FAILED".equals(JsonParser.parseString(Files.readString(root.resolve("job-"+job+".json"))).getAsJsonObject().get("status").getAsString()),"crash recovery public status");
    Files.writeString(root.resolve("job-"+job+".json"),JSON.toJson(map("status","QUEUED","message","queued")));
    BackupService.recoverInterrupted(db);
    check(Files.readString(root.resolve("job-"+job+".json")).contains("FAILED"),"unmarked queued job survived restart");
    Files.writeString(root.resolve("restore-marker.json"),JSON.toJson(map("jobId",job,"phase","READY","rollbackName",rollback.getFileName().toString(),"rollbackSha256","invalid")));
    try{BackupService.recoverInterrupted(db);throw new AssertionError("invalid rollback accepted");}catch(IOException expected){}
    check(original.equals(text(db,"SELECT name FROM branch WHERE id=1")),"invalid rollback changed original");
    Files.delete(root.resolve("restore-marker.json"));
    Files.writeString(root.resolve("restore-marker.json"),JSON.toJson(map("jobId",job,"phase","COMPLETED","rollbackName",rollback.getFileName().toString(),"rollbackSha256",sha(rollback))));
    change(db,"UPDATE branch SET name='committed restore' WHERE id=1");
    BackupService.recoverInterrupted(db);
    check("committed restore".equals(text(db,"SELECT name FROM branch WHERE id=1")),"completed restore was rolled back on restart");
  }
  private static Map<String,Object> preview(BackupService service,Path path,long actor)throws Exception {try(InputStream in=Files.newInputStream(path)){return service.preview(in,path.getFileName().toString(),actor,"other15");}}
  private static Map<String,Object> await(BackupService service,String job)throws Exception {
    long end=System.currentTimeMillis()+15000;Map<String,Object> status;
    do{status=service.jobStatus(job);if("SUCCEEDED".equals(status.get("status"))||"FAILED".equals(status.get("status")))return status;Thread.sleep(20);}while(System.currentTimeMillis()<end);
    throw new AssertionError("restore task timed out");
  }
  private static void seed(Path db)throws Exception {
    seedLegacy(db);
    change(db,"UPDATE ip_assignment SET switch_ip='10.8.0.8',serial_number='SN15' WHERE id=1");
    change(db,"UPDATE ip_address_pool SET is_occupied=1 WHERE id=3");
    new PhaseThreeService(db).forceRelease(2,2,"backup test release","stage15-release");
  }
  private static void seedLegacy(Path db)throws Exception {
    try(Connection c=open(db);PreparedStatement p=c.prepareStatement("INSERT INTO sys_user(id,username,password_hash,real_name,role,is_initial_admin,must_change_password) VALUES(?,?,?,?,?,?,0)")) {
      String hash=BCrypt.hashpw("BackupTest2026",BCrypt.gensalt(4));
      for(int i=1;i<=3;i++){p.setInt(1,i);p.setString(2,i==1?"admin15":i==2?"other15":"tech15");p.setString(3,hash);p.setString(4,"test user "+i);p.setString(5,i==3?"TECH_ADMIN":"ADMIN");p.setInt(6,i==1?1:0);p.executeUpdate();}
    }
    change(db,"INSERT INTO branch(id,code,name) VALUES(1,'B15','original branch')");
    change(db,"INSERT INTO outlet(id,branch_id,code,name) VALUES(1,1,'O15','test outlet')");
    change(db,"INSERT INTO ip_range(id,branch_id,outlet_id,cidr,subnet_mask,gateway,dns_primary,range_type) VALUES(1,1,1,'10.15.0.0/29','255.255.255.248','10.15.0.6','10.15.0.6','OFFICE')");
    for(int i=1;i<=3;i++)change(db,"INSERT INTO ip_address_pool(id,ip_range_id,ip_address) VALUES("+i+",1,'10.15.0."+i+"')");
    change(db,"INSERT INTO asset_definition(id,asset_type,name,requires_resource_code,recommended_range_type,created_by) VALUES(1,'TECH','test asset',1,'OFFICE',2)");
    change(db,"INSERT INTO ip_application(id,applicant_user_id,applicant_name,applicant_username,unified_auth_id,branch_id,outlet_id,asset_id,asset_type,asset_name,requires_user,requires_resource_code,asset_code,recommended_range_type,final_range_type,status,reviewed_by) VALUES(1,3,'test user 3','tech15','tech15',1,1,1,'TECH','test asset',0,1,'R15-1','OFFICE','OFFICE','APPROVED',2)");
    change(db,"INSERT INTO ip_assignment(id,application_id,ip_pool_id,resource_code,assigned_by,switch_port,mac_address) VALUES(1,1,1,'R15-1',2,'GE0/0/1','AA:BB:CC:DD:EE:FF')");
    change(db,"INSERT INTO ip_assignment(id,ip_pool_id,resource_code,assigned_by,assignment_source,manual_asset_id,manual_asset_type,manual_asset_name,manual_requires_user,manual_requires_resource_code,manual_asset_code,manual_registered_by,manual_registered_at,source_pool_state,source_is_active,source_is_reserved,switch_port,mac_address) VALUES(2,2,'R15-2',2,'MANUAL_REGISTRATION',1,'TECH','test asset',0,1,'R15-2',2,'2026-10-09T00:00:00Z','AVAILABLE',1,0,'GE0/0/2','11:22:33:44:55:66')");
    change(db,"INSERT INTO sys_session(token_hash,csrf_token_hash,user_id,expires_at,last_seen_at) VALUES('"+"a".repeat(64)+"','"+"b".repeat(64)+"',2,'2099-01-01T00:00:00Z','2026-10-09T00:00:00Z')");
    change(db,"INSERT INTO bulk_import_job(target_type,file_format,original_file_name,file_sha256,status,created_by) VALUES('OUTLET','CSV','pending.csv','"+"a".repeat(64)+"','PREVIEW_READY',2)");
    change(db,"INSERT INTO bulk_import_job(target_type,file_format,original_file_name,file_sha256,status,created_by) VALUES('OUTLET','CSV','history.csv','"+"b".repeat(64)+"','COMPLETED',2)");
    change(db,"INSERT INTO outlet_plan_import_job(file_format,original_file_name,file_sha256,status,created_by) VALUES('CSV','plan.csv','"+"c".repeat(64)+"','PREVIEW_READY',2)");
  }
  private static void migrate(Path path)throws Exception {Object database=database(path);Method m=database.getClass().getDeclaredMethod("migrate");m.setAccessible(true);m.invoke(database);}
  private static Object database(Path path)throws Exception {Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");Constructor<?> c=type.getDeclaredConstructor(Path.class);c.setAccessible(true);return c.newInstance(path);}
  private static void migrateLegacy(Path path)throws Exception {
    Object database=database(path);
    try(Connection c=open(path);Statement s=c.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");s.execute("CREATE TABLE schema_history (version TEXT PRIMARY KEY, description TEXT NOT NULL, checksum TEXT NOT NULL, installed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')))");
      for(int version=1;version<=21;version++) {
        if(version==20)continue;
        String method=version==13||version==14?"applyIpApplicationRebuild":version>=15&&version<=17?"applySoftDeleteReuseRebuild":"apply";
        Method m=database.getClass().getDeclaredMethod(method,Connection.class,String.class,String.class,String.class);m.setAccessible(true);
        String v=String.format(Locale.ROOT,"%03d",version);m.invoke(database,c,v,"stage15 legacy fixture","/db/migration/V"+v+"__"+MIGRATIONS[version-1]+".sql");
      }
    }
  }
  private static void copySnapshot(Path source,Path target)throws Exception {try(Connection c=open(source);Statement s=c.createStatement()){s.execute("VACUUM INTO '"+target.toString().replace("'","''")+"'");}}
  private static Connection open(Path db)throws Exception {Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;}
  private static void change(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement()){s.executeUpdate(sql);}}
  private static String text(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){if(!r.next())throw new AssertionError("missing result");return r.getString(1);}}
  private static long count(Path db,String sql)throws Exception {return Long.parseLong(text(db,sql));}
  private static void expectSqlFailure(Path db,String sql)throws Exception {try{change(db,sql);throw new AssertionError("initial administrator protection missing");}catch(SQLException expected){}}
  private interface Checked {void run()throws Exception;}
  private static void expectProblem(Checked action,String code)throws Exception {try{action.run();throw new AssertionError("invalid input accepted");}catch(PhaseTwoService.Problem p){if(code!=null&&!code.equals(p.code))throw new AssertionError("expected "+code+" but got "+p.code);}}
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  private static String sha(Path path)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(Files.readAllBytes(path));StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
  private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
}
