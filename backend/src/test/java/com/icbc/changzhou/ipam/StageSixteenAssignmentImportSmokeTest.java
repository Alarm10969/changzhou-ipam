package com.icbc.changzhou.ipam;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Isolated, public-service regressions for partial existing-assignment imports. */
public final class StageSixteenAssignmentImportSmokeTest {
  private static final String[] HEADERS={"IP地址","资产类型","资产名称","支行名称（核对用）","网点名称（核对用）","资源编码","使用人姓名","统一认证号","负责科技管理员账号","交换机IP","交换机端口","MAC地址","设备序列号","补录说明"};
  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("ipam-stage16-assignment-import-");
    verifyMixedImportAndRelease(work.resolve("mixed.db"));
    verifyConflictsAndRules(work.resolve("validation.db"));
    verifyReviewRequired(work.resolve("review.db"));
    verifyTransactionRollback(work.resolve("rollback.db"));
    verifySpreadsheetSafety(work.resolve("spreadsheet.db"));
    verifyBackupAndMigration(work.resolve("backup.db"),work);
    System.out.println("PASS|STAGE16_ASSIGNMENT_IMPORT|partial,duplicates,conflicts,validation,review,atomicity,xlsx,csv,release,backup,v022-upgrade|"+work);
  }

  private static void verifyMixedImportAndRelease(Path db)throws Exception {
    Fixture f=new Fixture(db);AssignmentImportService service=f.imports;
    change(db,"UPDATE ip_address_pool SET is_occupied=1 WHERE id="+f.pool(1));
    change(db,"UPDATE ip_address_pool SET is_active=0 WHERE id="+f.pool(2));
    change(db,"UPDATE ip_address_pool SET is_reserved=1,reservation_reason='原人工保留' WHERE id="+f.pool(3));
    long existing=n(f.manual.register(f.pool(4),json("assetId",f.plain),1).get("id"));
    long application=f.four.submitApplication(2,json("branchId",1,"outletId",1,"assetId",f.plain));
    f.four.approve(application,f.pool(5),"OFFICE","原审批意见",1L);
    long approved=assignment(db,f.pool(5));String approvedBefore=snapshot(db,approved);
    List<String[]> input=new ArrayList<>();
    String[] first=f.row(0,"存量设备");first[8]="tech16";first[9]="108.56.195.250";first[10]="GE0/0/1";first[11]=" 原始MAC ";first[12]="SN16";first[13]="=SUM(1,1)";input.add(first);
    for(int i=1;i<=5;i++)input.add(f.row(i,"存量设备"));
    String[] outside=f.row(6,"存量设备");outside[0]="192.0.2.123";outside[13]="=SUM(1,1)";input.add(outside);
    long before=countAssignments(db),applications=scalar(db,"SELECT count(*) FROM ip_application");
    Map<String,Object> preview=service.preview("mixed.csv",csv(input),1);
    check(n(preview.get("totalRows"))==7&&n(preview.get("readyRows"))==4&&n(preview.get("duplicateRows"))==1&&n(preview.get("errorRows"))==2,"mixed preview counts");
    for(int i=2;i<=5;i++)result(preview,i,"READY");result(preview,6,"DUPLICATE");result(preview,7,"ERROR");result(preview,8,"ERROR");
    check(countAssignments(db)==before,"preview must not allocate");
    long job=n(preview.get("id"));int version=(int)n(preview.get("previewVersion"));
    String previewReport=new String(service.report(job,1),StandardCharsets.UTF_8);
    check(previewReport.contains("'=SUM(1,1)")&&!previewReport.contains(",=SUM(1,1)"),"CSV report allows spreadsheet formula injection");
    check(!previewReport.contains("SN16"),"preview not-passing report included a READY row");
    expect("PARTIAL_IMPORT_CONFIRMATION_REQUIRED",()->service.confirm(job,1,version,false));
    check(countAssignments(db)==before,"unacknowledged partial confirmation wrote assignments");
    Map<String,Object> done=service.confirm(job,1,version,true);
    check("COMPLETED".equals(done.get("status"))&&n(done.get("importedRows"))==4&&n(done.get("notImportedRows"))==3,"partial completion counts");
    check(countAssignments(db)==before+4&&scalar(db,"SELECT count(*) FROM ip_application")==applications,"batch creates assignments without approval applications");
    check(approvedBefore.equals(snapshot(db,approved)),"existing approval was overwritten");
    check(existing==assignment(db,f.pool(4)),"identical manual registration duplicated");
    for(int i=0;i<4;i++){long id=assignment(db,f.pool(i));check("MANUAL_REGISTRATION".equals(text(db,"SELECT assignment_source FROM ip_assignment WHERE id="+id)),"batch uses manual assignment lifecycle");}
    check(" 原始MAC ".equals(text(db,"SELECT mac_address FROM ip_assignment WHERE ip_pool_id="+f.pool(0))),"import did not preserve original MAC text");
    check(scalar(db,"SELECT is_occupied FROM ip_address_pool WHERE id="+f.pool(1))==0,"occupied state not cleared on registration");
    long audits=scalar(db,"SELECT count(*) FROM audit_log");
    Map<String,Object> repeated=service.confirm(job,1,version-1,true);
    check(n(repeated.get("importedRows"))==4&&countAssignments(db)==before+4&&scalar(db,"SELECT count(*) FROM audit_log")==audits,"completion retry must be idempotent");
    String report=new String(service.report(job,1),StandardCharsets.UTF_8);
    check(report.contains("192.0.2.123")&&!report.contains("SN16"),"unimported report lost errors or included successful rows");
    check("COMPLETED".equals(service.get(job,1).get("status")),"completed result missing");service.recent(1);
    Map<String,Object> identical=service.preview("identical.csv",csv(Collections.singletonList(first)),1);
    check(n(identical.get("duplicateRows"))==1&&n(identical.get("readyRows"))==0,"same imported assignment must be a repeat skip");
    expect("IMPORT_NO_READY_ROWS",()->service.confirm(n(identical.get("id")),1,(int)n(identical.get("previewVersion")),true));
    check("PREVIEW_READY".equals(service.get(n(identical.get("id")),1).get("status")),"duplicate-only confirmation completed an empty batch");
    service.cancel(n(identical.get("id")),1);
    for(int i=0;i<4;i++)f.three.forceRelease(assignment(db,f.pool(i)),1,"批量补录回归释放","stage16-release-"+i);
    check("AVAILABLE".equals(f.state(0))&&"AVAILABLE".equals(f.state(1)),"available and occupied registrations release to available");
    check("DISABLED".equals(f.state(2))&&"MANUAL_RESERVED".equals(f.state(3)),"release lost disabled/reserved source state");
    check("原人工保留".equals(text(db,"SELECT reservation_reason FROM ip_address_pool WHERE id="+f.pool(3))),"release lost original reservation reason");
    check(scalar(db,"SELECT count(*) FROM ip_release_event WHERE request_id LIKE 'stage16-release-%'")==4,"batch registration release history missing");
    check("SN16".equals(text(db,"SELECT serial_number FROM ip_release_event WHERE request_id='stage16-release-0'"))&&"=SUM(1,1)".equals(text(db,"SELECT manual_note FROM ip_release_event WHERE request_id='stage16-release-0'")),"release history lost import details");
    integrity(db);
  }

  private static void verifyConflictsAndRules(Path db)throws Exception {
    Fixture f=new Fixture(db);List<String[]> rows=new ArrayList<>();
    String[] duplicateA=f.row(0,"存量设备"),duplicateB=f.row(0,"存量设备");duplicateA[13]="A";duplicateB[13]="B";rows.add(duplicateA);rows.add(duplicateB);
    String[] codeA=f.row(1,"编码设备"),codeB=f.row(2,"编码设备");codeA[5]="SAME-CODE";codeB[5]="SAME-CODE";rows.add(codeA);rows.add(codeB);
    rows.add(f.row(3,"编码设备"));
    String[] forbiddenCode=f.row(4,"存量设备");forbiddenCode[5]="FORBIDDEN";rows.add(forbiddenCode);
    String[] missingUser=f.row(5,"人员设备");missingUser[1]="TECH";missingUser[5]="NEEDS-USER";rows.add(missingUser);
    String[] forbiddenUser=f.row(6,"存量设备");forbiddenUser[6]="错误使用人";forbiddenUser[7]="123456";rows.add(forbiddenUser);
    String[] disabled=f.row(7,"停用设备");rows.add(disabled);
    String[] wrongBranch=f.row(8,"存量设备");wrongBranch[3]="另一个支行";rows.add(wrongBranch);
    String[] wrongOutlet=f.row(9,"存量设备");wrongOutlet[4]="另一个网点";rows.add(wrongOutlet);
    String[] wrongTech=f.row(10,"存量设备");wrongTech[8]="othertech16";rows.add(wrongTech);
    String[] inactiveTech=f.row(11,"存量设备");inactiveTech[8]="inactivetech16";rows.add(inactiveTech);
    String[] gateway=f.row(12,"存量设备");gateway[0]=text(db,"SELECT gateway FROM ip_range WHERE range_type='OFFICE'");rows.add(gateway);
    String[] notPooled=f.row(13,"存量设备");notPooled[0]="192.0.2.199";rows.add(notPooled);
    f.manual.register(f.pool(14),json("assetId",f.coded,"assetCode","EXISTING-CODE"),1);
    String[] existingCode=f.row(15,"编码设备");existingCode[5]="EXISTING-CODE";rows.add(existingCode);
    Map<String,Object> preview=f.imports.preview("validation.csv",csv(rows),1);
    check(n(preview.get("totalRows"))==rows.size()&&n(preview.get("errorRows"))==rows.size()&&n(preview.get("readyRows"))==0,"conditional rules or conflict rows accepted");
    for(Map<String,Object> row:items(preview))check("ERROR".equals(row.get("result"))&&row.get("code")!=null&&!String.valueOf(row.get("message")).isEmpty(),"error row lacks actionable validation");
    rowCode(preview,2,"BATCH_IP_CONFLICT");rowCode(preview,3,"BATCH_IP_CONFLICT");rowCode(preview,4,"BATCH_RESOURCE_CODE_CONFLICT");rowCode(preview,5,"BATCH_RESOURCE_CODE_CONFLICT");
    rowCode(preview,6,"ASSET_CODE_REQUIRED");rowCode(preview,7,"ASSET_CODE_FORBIDDEN");rowCode(preview,8,"ASSET_USER_REQUIRED");rowCode(preview,9,"ASSET_USER_FORBIDDEN");
    rowCode(preview,10,"ASSET_INVALID");rowCode(preview,11,"BRANCH_MISMATCH");rowCode(preview,12,"OUTLET_MISMATCH");rowCode(preview,13,"RESPONSIBLE_TECH_INVALID");rowCode(preview,14,"RESPONSIBLE_TECH_INVALID");
    rowCode(preview,15,"SYSTEM_RESERVED_PROTECTED");rowCode(preview,16,"IP_NOT_FOUND");rowCode(preview,17,"RESOURCE_CODE_DUPLICATE");
    check(countAssignments(db)==1,"error-only preview wrote allocations");
    long job=n(preview.get("id"));expectAnyProblem(()->f.imports.get(job,3));expectAnyProblem(()->f.imports.report(job,3));expectAnyProblem(()->f.imports.cancel(job,3));
    expect("IMPORT_NO_READY_ROWS",()->f.imports.confirm(job,1,(int)n(preview.get("previewVersion")),true));
    check("PREVIEW_READY".equals(f.imports.get(job,1).get("status")),"error-only confirmation completed an empty batch");
    f.imports.cancel(job,1);f.imports.cancel(job,1);
    check("CANCELLED".equals(f.imports.get(job,1).get("status")),"cancel state missing");
    expect("IMPORT_NOT_CONFIRMABLE",()->f.imports.confirm(job,1,(int)n(preview.get("previewVersion")),true));
    integrity(db);
  }

  private static void verifyReviewRequired(Path db)throws Exception {
    Fixture f=new Fixture(db);String[] coded=f.row(1,"编码设备");coded[5]="REVIEW-CODE";
    Map<String,Object> p=f.imports.preview("race.csv",csv(Arrays.asList(f.row(0,"存量设备"),coded)),1);
    long job=n(p.get("id"));int version=(int)n(p.get("previewVersion"));
    Map<String,Object> wrongVersion=f.imports.confirm(job,1,version+1,true);
    check(Boolean.TRUE.equals(wrongVersion.get("requiresReview"))&&countAssignments(db)==0,"stale preview version wrote assignments");
    f.manual.register(f.pool(0),json("assetId",f.plain,"note","其他管理员先补录"),3);
    Map<String,Object> changed=f.imports.confirm(job,1,version,true);
    check(Boolean.TRUE.equals(changed.get("requiresReview"))&&n(changed.get("previewVersion"))>version&&countAssignments(db)==1,"allocation race must return review without partial writes");
    result(changed,2,"ERROR");result(changed,3,"READY");
    Map<String,Object> old=f.imports.confirm(job,1,version,true);
    check(Boolean.TRUE.equals(old.get("requiresReview"))&&countAssignments(db)==1,"old preview remained confirmable after recalculation");
    Map<String,Object> completed=f.imports.confirm(job,1,(int)n(changed.get("previewVersion")),true);
    check(n(completed.get("importedRows"))==1&&countAssignments(db)==2,"reviewed remaining row not imported");
    Map<String,Object> rule=f.imports.preview("asset-change.csv",csv(Arrays.asList(f.row(2,"存量设备"),f.row(3,"存量设备"))),1);
    f.four.updateAsset(f.plain,json("assetType","NON_TECH","name","存量设备","requiresUser",0,"requiresResourceCode",1,"recommendedRangeType","OFFICE","active",1),1);
    Map<String,Object> ruleChanged=f.imports.confirm(n(rule.get("id")),1,(int)n(rule.get("previewVersion")),true);
    check(Boolean.TRUE.equals(ruleChanged.get("requiresReview"))&&n(ruleChanged.get("errorRows"))==2&&countAssignments(db)==2,"asset rule changes bypassed second validation");
    integrity(db);
  }

  private static void verifyTransactionRollback(Path db)throws Exception {
    Fixture f=new Fixture(db);change(db,"UPDATE ip_address_pool SET is_occupied=1 WHERE id="+f.pool(0));
    Map<String,Object> p=f.imports.preview("atomic.csv",csv(Arrays.asList(f.row(0,"存量设备"),f.row(1,"存量设备"))),1);
    long job=n(p.get("id"));int version=(int)n(p.get("previewVersion"));
    change(db,"CREATE TRIGGER stage16_force_second_failure BEFORE INSERT ON ip_assignment WHEN NEW.ip_pool_id="+f.pool(1)+" BEGIN SELECT RAISE(ABORT,'stage16 forced failure'); END");
    boolean failed=false;try{f.imports.confirm(job,1,version,true);}catch(Exception expected){failed=true;}
    check(failed&&countAssignments(db)==0,"SQL failure did not roll back every READY row");
    check(scalar(db,"SELECT is_occupied FROM ip_address_pool WHERE id="+f.pool(0))==1,"failed transaction lost original occupied state");
    Map<String,Object> retry=f.imports.get(job,1);check("PREVIEW_READY".equals(retry.get("status"))&&n(retry.get("readyRows"))==2,"transaction failure corrupted retryable job");
    change(db,"DROP TRIGGER stage16_force_second_failure");
    check(n(f.imports.confirm(job,1,(int)n(retry.get("previewVersion")),true).get("importedRows"))==2,"failed transaction could not retry");
    integrity(db);
  }

  private static void verifySpreadsheetSafety(Path db)throws Exception {
    Fixture f=new Fixture(db);byte[] template=f.imports.template("XLSX");
    try(XSSFWorkbook wb=new XSSFWorkbook(new ByteArrayInputStream(template))){Sheet data=wb.getSheet("导入数据");check(data!=null,"XLSX data sheet missing");check(data.getRow(0).getLastCellNum()==HEADERS.length,"XLSX template column count");for(int i=0;i<HEADERS.length;i++)check(HEADERS[i].equals(data.getRow(0).getCell(i).getStringCellValue()),"XLSX template header "+i);}
    String csvTemplate=new String(f.imports.template("CSV"),StandardCharsets.UTF_8);for(String header:HEADERS)check(csvTemplate.contains(header),"CSV template header "+header);
    String[] numeric=f.row(0,"人员设备");numeric[1]="TECH";numeric[5]="000123";numeric[6]="数字编号使用人";numeric[7]="000456";
    Map<String,Object> p=f.imports.preview("numeric.xlsx",workbook(Collections.singletonList(numeric),true,false),1);
    check(n(p.get("readyRows"))==1,"formatted numeric resource code or authentication ID rejected");
    f.imports.confirm(n(p.get("id")),1,(int)n(p.get("previewVersion")),true);
    check("000123".equals(text(db,"SELECT resource_code FROM ip_assignment WHERE ip_pool_id="+f.pool(0)))&&"000456".equals(text(db,"SELECT manual_user_unified_auth_id FROM ip_assignment WHERE ip_pool_id="+f.pool(0))),"formatted numeric identifiers lost zeros");
    Map<String,Object> repeated=f.imports.preview("same-file.csv",csv(Arrays.asList(f.row(1,"存量设备"),f.row(1,"存量设备"))),1);
    check(n(repeated.get("readyRows"))==1&&n(repeated.get("duplicateRows"))==1,"identical file rows should allocate once");
    Map<String,Object> repeatedDone=f.imports.confirm(n(repeated.get("id")),1,(int)n(repeated.get("previewVersion")),true);
    check(n(repeatedDone.get("importedRows"))==1&&countAssignments(db)==2,"identical file rows allocated more than once");
    long before=scalar(db,"SELECT count(*) FROM assignment_import_job");
    byte[] padded=("\n".repeat(2001)+new String(csv(Collections.singletonList(f.row(2,"存量设备"))),StandardCharsets.UTF_8).replace("\uFEFF","")+"\n".repeat(2001)).getBytes(StandardCharsets.UTF_8);
    Map<String,Object> blankLines=f.imports.preview("blank-lines.csv",padded,1);
    check(n(blankLines.get("totalRows"))==1&&n(blankLines.get("readyRows"))==1,"blank lines count toward data-row limit");
    before=scalar(db,"SELECT count(*) FROM assignment_import_job");
    String[] invalid=f.row(2,"存量设备");invalid[0]="not-an-ip";List<String[]> excessive=Collections.nCopies(2001,invalid);
    expectAnyProblem(()->f.imports.preview("over-limit.csv",csv(excessive),1));
    check(scalar(db,"SELECT count(*) FROM assignment_import_job")==before,"invalid rows bypassed the 2000-row file limit");
    expectAnyProblem(()->f.imports.preview("formula.xlsx",workbook(Collections.singletonList(f.row(1,"存量设备")),false,true),1));
    check(scalar(db,"SELECT count(*) FROM assignment_import_job")==before,"rejected formula workbook created a preview job");
    byte[] linked;
    try(XSSFWorkbook wb=new XSSFWorkbook();XSSFWorkbook foreign=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      Sheet data=wb.createSheet("导入数据");Row head=data.createRow(0);for(int i=0;i<HEADERS.length;i++)head.createCell(i).setCellValue(HEADERS[i]);foreign.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("external");wb.linkExternalWorkbook("external.xlsx",foreign);wb.write(out);linked=out.toByteArray();
    }
    final byte[] external=linked;expectAnyProblem(()->f.imports.preview("external.xlsx",external,1));
    check(scalar(db,"SELECT count(*) FROM assignment_import_job")==before,"rejected external-link workbook created a preview job");
    integrity(db);
  }

  private static void verifyBackupAndMigration(Path db,Path work)throws Exception {
    Fixture f=new Fixture(db);Map<String,Object> complete=f.imports.preview("completed.csv",csv(Collections.singletonList(f.row(0,"存量设备"))),1);
    long completedId=n(complete.get("id"));f.imports.confirm(completedId,1,(int)n(complete.get("previewVersion")),true);
    Map<String,Object> pending=f.imports.preview("pending.csv",csv(Collections.singletonList(f.row(1,"存量设备"))),1);long pendingId=n(pending.get("id"));
    MaintenanceGate gate=new MaintenanceGate();
    try(BackupService backups=new BackupService(db,gate,StageSixteenAssignmentImportSmokeTest::migrate)){
      Path archive=work.resolve("assignment-import-backup.zip");try(BackupService.BackupFile file=backups.download(1,"admin16")){Files.copy(file.path,archive);}
      Map<String,Object> preview;try(InputStream in=Files.newInputStream(archive)){preview=backups.preview(in,"assignment-import-backup.zip",1,"admin16");}
      check(n(preview.get("sourceSchema"))==24&&n(preview.get("targetSchema"))==24,"backup schema missing assignment import migration");
      Map<?,?> counts=(Map<?,?>)preview.get("counts");check(n(counts.get("assignment_import_job"))==2&&n(counts.get("assignment_import_row"))==2,"backup preview omitted assignment import tables");
      String restore=String.valueOf(backups.confirm(String.valueOf(preview.get("uploadId")),1,"admin16").get("jobId"));
      long deadline=System.currentTimeMillis()+15000;Map<String,Object> status;
      do{status=backups.jobStatus(restore);if("SUCCEEDED".equals(status.get("status"))||"FAILED".equals(status.get("status")))break;Thread.sleep(20);}while(System.currentTimeMillis()<deadline);
      check("SUCCEEDED".equals(status.get("status")),"assignment-import backup restore failed");
      check("EXPIRED".equals(f.imports.get(pendingId,1).get("status")),"restore retained confirmable pending assignment import");
      check("COMPLETED".equals(f.imports.get(completedId,1).get("status"))&&countAssignments(db)==1,"restore lost completed assignment import/history");
      expect("IMPORT_NOT_CONFIRMABLE",()->f.imports.confirm(pendingId,1,(int)n(pending.get("previewVersion")),true));
    }
    // V023 is additive. Re-create the exact pre-migration state from this isolated fixture.
    change(db,"DROP TABLE assignment_import_row");change(db,"DROP TABLE assignment_import_job");change(db,"DELETE FROM schema_history WHERE version='023'");
    String existing=snapshot(db,assignment(db,f.pool(0)));long history=scalar(db,"SELECT count(*) FROM ip_release_event");
    migrate(db);migrate(db);
    check(scalar(db,"SELECT count(*) FROM schema_history WHERE version='023'")==1&&scalar(db,"SELECT count(*) FROM assignment_import_job")==0,"V022 to V023 migration not additive/idempotent");
    check(existing.equals(snapshot(db,assignment(db,f.pool(0))))&&scalar(db,"SELECT count(*) FROM ip_release_event")==history,"V023 migration changed existing assignments/history");integrity(db);
  }

  private static final class Fixture {
    final Path db;final PhaseFourService four;final PhaseThreeService three;final ManualAssignmentService manual;final AssignmentImportService imports;final List<Long> free=new ArrayList<>();final long plain,coded;
    Fixture(Path file)throws Exception {
      db=file;migrate(db);try(Connection c=open(db);Statement s=c.createStatement()){
        s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('admin16','x','管理员','ADMIN',1,0,1)");
        s.execute("INSERT INTO branch(code,name,is_active) VALUES('B16','阶段16支行',1),('OTHER16','另一个支行',1)");
        s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'O16','阶段16网点',1),(2,'OTHER16','另一个网点',1)");
        s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin,branch_id,outlet_id) VALUES('tech16','x','科技管理员','TECH_ADMIN',1,0,0,1,1)");
        s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('otheradmin16','x','其他管理员','ADMIN',1,0,0)");
        s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin,branch_id,outlet_id) VALUES('othertech16','x','其他科技管理员','TECH_ADMIN',1,0,0,2,2),('inactivetech16','x','停用科技管理员','TECH_ADMIN',0,0,0,1,1)");
      }
      four=new PhaseFourService(db);three=new PhaseThreeService(db);manual=new ManualAssignmentService(db);imports=new AssignmentImportService(db);
      four.createPlan(json("branchId",1,"outletId",1,"productionCidr","108.56.64.0/27"),1);
      plain=n(four.createAsset(json("assetType","NON_TECH","name","存量设备","requiresUser",0,"requiresResourceCode",0,"recommendedRangeType","OFFICE","active",1),1).get("id"));
      coded=n(four.createAsset(json("assetType","NON_TECH","name","编码设备","requiresUser",0,"requiresResourceCode",1,"recommendedRangeType","OFFICE","active",1),1).get("id"));
      four.createAsset(json("assetType","TECH","name","人员设备","requiresUser",1,"requiresResourceCode",1,"recommendedRangeType","OFFICE","active",1),1);
      four.createAsset(json("assetType","NON_TECH","name","停用设备","requiresUser",0,"requiresResourceCode",0,"recommendedRangeType","OFFICE","active",0),1);
      try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT p.id FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id WHERE r.range_type='OFFICE' AND p.is_active=1 AND p.is_reserved=0 ORDER BY p.id")){while(r.next())free.add(r.getLong(1));}
      check(free.size()>=16,"isolated fixture pool too small");
    }
    long pool(int index){return free.get(index);}
    String[] row(int index,String asset)throws Exception {String[] row=new String[HEADERS.length];Arrays.fill(row,"");row[0]=text(db,"SELECT ip_address FROM ip_address_pool WHERE id="+pool(index));row[1]="NON_TECH";row[2]=asset;row[3]="阶段16支行";row[4]="阶段16网点";return row;}
    String state(int index)throws Exception {long pool=pool(index);if(scalar(db,"SELECT count(*) FROM ip_assignment WHERE status='ASSIGNED' AND ip_pool_id="+pool)>0)return "ASSIGNED";if(scalar(db,"SELECT is_occupied FROM ip_address_pool WHERE id="+pool)==1)return "OCCUPIED";if(scalar(db,"SELECT is_reserved FROM ip_address_pool WHERE id="+pool)==1)return "MANUAL_RESERVED";return scalar(db,"SELECT is_active FROM ip_address_pool WHERE id="+pool)==1?"AVAILABLE":"DISABLED";}
  }

  private static byte[] workbook(List<String[]> rows,boolean numeric,boolean formula)throws Exception {
    try(XSSFWorkbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      Sheet s=wb.createSheet("导入数据");Row header=s.createRow(0);for(int i=0;i<HEADERS.length;i++)header.createCell(i).setCellValue(HEADERS[i]);
      CellStyle codeStyle=wb.createCellStyle();codeStyle.setDataFormat(wb.createDataFormat().getFormat("000000"));
      for(int index=0;index<rows.size();index++){Row row=s.createRow(index+1);for(int i=0;i<HEADERS.length;i++){Cell cell=row.createCell(i);cell.setCellValue(rows.get(index)[i]);if(numeric&&(i==5||i==7)){cell.setCellValue(i==5?123:456);cell.setCellStyle(codeStyle);}}}
      if(formula)wb.createSheet("说明").createRow(0).createCell(0).setCellFormula("1+1");wb.write(out);return out.toByteArray();
    }
  }
  private static byte[] csv(List<String[]> rows){StringBuilder csv=new StringBuilder("\uFEFF");csvRow(csv,HEADERS);for(String[] row:rows)csvRow(csv,row);return csv.toString().getBytes(StandardCharsets.UTF_8);}
  private static void csvRow(StringBuilder out,String[] cells){for(int i=0;i<cells.length;i++){if(i>0)out.append(',');out.append('"').append(cells[i].replace("\"","\"\"")).append('"');}out.append("\r\n");}
  @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> preview){return (List<Map<String,Object>>)preview.get("rows");}
  private static void result(Map<String,Object> preview,int number,String expected){for(Map<String,Object> row:items(preview))if(n(row.get("rowNumber"))==number){check(expected.equals(row.get("result")),"row "+number+" expected "+expected+", got "+row);return;}throw new AssertionError("missing row "+number);}
  private static void rowCode(Map<String,Object> preview,int number,String expected){for(Map<String,Object> row:items(preview))if(n(row.get("rowNumber"))==number){check(expected.equals(row.get("code")),"row "+number+" expected code "+expected+", got "+row);return;}throw new AssertionError("missing row "+number);}
  private static long countAssignments(Path db)throws Exception{return scalar(db,"SELECT count(*) FROM ip_assignment");}
  private static long assignment(Path db,long pool)throws Exception{return scalar(db,"SELECT id FROM ip_assignment WHERE status='ASSIGNED' AND ip_pool_id="+pool);}
  private static String snapshot(Path db,long assignment)throws Exception {try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM ip_assignment WHERE id="+assignment)){check(r.next(),"missing assignment snapshot");List<String> cells=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)cells.add(r.getString(i));return new Gson().toJson(cells);}}
  private static void migrate(Path db)throws Exception {Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");Constructor<?> constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);Object database=constructor.newInstance(db);Method migrate=type.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(database);}
  private static Connection open(Path db)throws Exception {Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;}
  private static void change(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement()){s.execute(sql);}}
  private static long scalar(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){check(r.next(),"scalar missing: "+sql);return r.getLong(1);}}
  private static String text(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){check(r.next(),"text missing: "+sql);return r.getString(1);}}
  private static void integrity(Path db)throws Exception {check("ok".equals(text(db,"PRAGMA integrity_check"))&&scalar(db,"SELECT count(*) FROM pragma_foreign_key_check")==0,"database integrity");}
  private static JsonObject json(Object...items){JsonObject out=new JsonObject();for(int i=0;i<items.length;i+=2){Object value=items[i+1];String key=String.valueOf(items[i]);if(value==null)out.add(key,JsonNull.INSTANCE);else if(value instanceof Number)out.addProperty(key,(Number)value);else out.addProperty(key,String.valueOf(value));}return out;}
  private static long n(Object value){return value==null?0:((Number)value).longValue();}
  private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  private static void expect(String code,Operation action)throws Exception {try{action.run();throw new AssertionError("expected "+code);}catch(PhaseTwoService.Problem error){if(!code.equals(error.code))throw new AssertionError("expected "+code+", got "+error.code,error);}}
  private static void expectAnyProblem(Operation action)throws Exception {try{action.run();throw new AssertionError("expected validation/access rejection");}catch(PhaseTwoService.Problem expected){}}
  private interface Operation{void run()throws Exception;}
}
