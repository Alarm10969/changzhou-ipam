package com.icbc.changzhou.ipam;

import com.google.gson.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Resource maintenance, occupied-state lifecycle and lossless V021 upgrade regression. */
public final class StageFourteenResourceMaintenanceSmokeTest {
  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("ipam-stage14-maintenance-");
    verifyUpgrade(work.resolve("v021-upgrade.db"));
    verifyStatisticsCoexistence(work.resolve("six-state-statistics.db"));
    Path db=work.resolve("maintenance.db");migrate(db);seed(db);
    PhaseFourService four=new PhaseFourService(db);
    PhaseTwoService two=new PhaseTwoService(db);
    PhaseThreeService three=new PhaseThreeService(db);
    ManualAssignmentService manual=new ManualAssignmentService(db);
    four.createPlan(json("branchId",1,"outletId",1,"productionCidr","108.56.64.0/27"),1L);
    long coded=asset(four,"设备",true),plain=asset(four,"无编码设备",false);
    List<Long> free=available(db,"OFFICE");
    long occupiedPool=free.get(0),recoveredPool=free.get(1),atomicPool=free.get(2);
    long initialAvailable=n(three.overview(null,null).get("availableCount"));
    check(two.updatePoolBatch(batch("OCCUPIED",occupiedPool,recoveredPool),1L)==2,"batch count");
    check("OCCUPIED".equals(pool(four,db,occupiedPool).get("state")),"occupied pool state");
    check(n(four.pool(null,null,null,"OCCUPIED","",1).get("total"))==2,"occupied state filter");
    check(n(four.pool(null,null,null,"AVAILABLE",address(db,occupiedPool),1).get("total"))==0,"occupied excluded from available filter");
    Map<String,Object> overview=three.overview(null,null);
    check(n(overview.get("availableCount"))==initialAvailable-2&&n(overview.get("occupiedCount"))==2&&n(overview.get("assignedCount"))==0,"occupied statistics");
    check(n(overview.get("usedCount"))==2&&((Number)overview.get("usageRate")).doubleValue()>0,"occupied contributes to usage");
    long app=four.submitApplication(2L,json("branchId",1,"outletId",1,"assetId",coded,"assetCode","ORIGINAL"));
    for(Map<String,Object> candidate:(List<Map<String,Object>>)four.candidates(app,"OFFICE",address(db,occupiedPool),1).get("items"))check(n(candidate.get("id"))!=occupiedPool,"occupied excluded from approval candidates");
    expect("IP_NOT_AVAILABLE",()->four.approve(app,occupiedPool,"OFFICE","",1L));
    expect("IP_NOT_AVAILABLE",()->two.updatePoolBatch(batch("OCCUPIED",atomicPool,occupiedPool),1L));
    check("AVAILABLE".equals(pool(four,db,atomicPool).get("state")),"batch conflict rolls back every row");
    long gateway=scalar(db,"SELECT p.id FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id WHERE p.ip_address=r.gateway LIMIT 1");
    expect("SYSTEM_RESERVED_PROTECTED",()->two.updatePoolBatch(batch("OCCUPIED",atomicPool,gateway),1L));
    check("AVAILABLE".equals(pool(four,db,atomicPool).get("state")),"gateway conflict is atomic");
    long range=scalar(db,"SELECT ip_range_id FROM ip_address_pool WHERE id="+occupiedPool);
    expect("GATEWAY_TARGET_OCCUPIED",()->manual.adjustGateway(range,json("newGateway",address(db,occupiedPool),"expectedGateway",text(db,"SELECT gateway FROM ip_range WHERE id="+range)),1L));
    two.updatePoolBatch(batch("AVAILABLE",recoveredPool),1L);
    check("AVAILABLE".equals(pool(four,db,recoveredPool).get("state")),"occupied can recover available");
    long beforeApplications=scalar(db,"SELECT count(*) FROM ip_application");
    long registered=n(manual.register(occupiedPool,json("assetId",coded,"assetCode","MANUAL-OLD","switchIp","108.56.195.250","switchPort","36","macAddress"," 设备 MAC 标签 ","serialNumber","SN-MANUAL"),1L).get("id"));
    check(scalar(db,"SELECT count(*) FROM ip_application")==beforeApplications,"manual registration never creates application");
    check("ASSIGNED".equals(pool(four,db,occupiedPool).get("state"))&&scalar(db,"SELECT is_occupied FROM ip_address_pool WHERE id="+occupiedPool)==0,"occupied registration becomes assigned");
    check("AVAILABLE".equals(text(db,"SELECT source_pool_state FROM ip_assignment WHERE id="+registered)),"occupied registration releases to available");
    check(" 设备 MAC 标签 ".equals(text(db,"SELECT mac_address FROM ip_assignment WHERE id="+registered)),"manual MAC retains original input");
    four.updateAssignmentInformation(registered,json("poolId",occupiedPool,"expectedVersion",0,"resourceCode","MANUAL-NEW"),1L);
    check("MANUAL-NEW".equals(pool(four,db,occupiedPool).get("manualAssetCode")),"manual current code updated");
    check("MANUAL-OLD".equals(text(db,"SELECT manual_asset_code FROM ip_assignment WHERE id="+registered)),"manual original registration snapshot preserved");
    three.forceRelease(registered,1L,"补录回归释放","stage14-manual");
    check("AVAILABLE".equals(pool(four,db,occupiedPool).get("state")),"occupied registration release available");
    check("SN-MANUAL".equals(text(db,"SELECT serial_number FROM ip_release_event WHERE assignment_id="+registered)),"manual serial release snapshot");

    four.approve(app,recoveredPool,"OFFICE","资料录入",null,"108.56.195.250","36","不限定 MAC格式","SN-ORIGINAL",1L);
    long assigned=assignment(db,recoveredPool);
    Map<String,Object> changed=four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",0,"resourceCode","CURRENT","switchIp","108.56.195.251","switchPort","GE0/0/1","macAddress"," aa-bb weird 字符 ","serialNumber","SN-CURRENT"),1L);
    check(Boolean.TRUE.equals(changed.get("changed"))&&n(changed.get("version"))==1,"information updates as one version");
    Map<String,Object> current=pool(four,db,recoveredPool);
    check("CURRENT".equals(current.get("resourceCode"))&&"CURRENT".equals(current.get("assetCode"))&&n(current.get("requiresResourceCode"))==1,"pool current code and flag");
    check(String.valueOf(current.get("allocationSummary")).contains("CURRENT")&&!String.valueOf(current.get("allocationSummary")).contains("ORIGINAL"),"summary uses current code");
    check("ORIGINAL".equals(text(db,"SELECT asset_code FROM ip_application WHERE id="+app)),"application original code snapshot preserved");
    check(n(four.pool(null,null,null,null,"CURRENT",1).get("total"))==1&&n(four.pool(null,null,null,null,"ORIGINAL",1).get("total"))==0,"resource search uses current code");
    check(" aa-bb weird 字符 ".equals(current.get("macAddress")),"MAC edited without normalization");
    check("SN-CURRENT".equals(current.get("serialNumber"))&&"108.56.195.251".equals(current.get("switchIp")),"serial and switch IP editable");
    long audits=scalar(db,"SELECT count(*) FROM audit_log WHERE action='UPDATE_ASSIGNMENT_INFORMATION'");
    Map<String,Object> same=four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",1,"resourceCode","CURRENT","switchIp","108.56.195.251","switchPort","GE0/0/1","macAddress"," aa-bb weird 字符 ","serialNumber","SN-CURRENT"),1L);
    check(Boolean.FALSE.equals(same.get("changed"))&&scalar(db,"SELECT count(*) FROM audit_log WHERE action='UPDATE_ASSIGNMENT_INFORMATION'")==audits,"no-op keeps version and audit unchanged");
    expect("ALLOCATION_STALE",()->four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",0,"serialNumber","WRONG"),1L));
    expect("ASSET_CODE_REQUIRED",()->four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",1,"resourceCode",""),1L));
    expect("SWITCH_IP_INVALID",()->four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",1,"switchIp","999.56.1.1"),1L));
    expect("MAC_INVALID",()->four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",1,"macAddress","text\n"),1L));
    expect("SERIAL_NUMBER_INVALID",()->four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",1,"serialNumber","SN\u0000"),1L));
    expect("FORBIDDEN",()->four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",1,"serialNumber","TECH"),2L));
    long secondPool=free.get(3),secondApp=four.submitApplication(2L,json("branchId",1,"outletId",1,"assetId",coded,"assetCode","DUPLICATE"));
    four.approve(secondApp,secondPool,"OFFICE","",null,null,null,null,null,1L);
    long second=assignment(db,secondPool);
    expect("RESOURCE_CODE_DUPLICATE",()->four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",1,"resourceCode","DUPLICATE"),1L));
    long plainPool=free.get(4),plainApp=four.submitApplication(2L,json("branchId",1,"outletId",1,"assetId",plain));
    four.approve(plainApp,plainPool,"OFFICE","",1L);
    expect("ASSET_CODE_FORBIDDEN",()->four.updateAssignmentInformation(assignment(db,plainPool),json("poolId",plainPool,"expectedVersion",0,"resourceCode","FORBIDDEN"),1L));
    assertPrivate(four.applicationsForApplicant(null,null,2L));assertPrivate(three.myAssignments(2L));
    verifyExports(three,"108.56.195.251","GE0/0/1","SN-CURRENT"," aa-bb weird 字符 ","CURRENT");
    long version=n(four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",1,"switchIp","","switchPort","","macAddress"," ","serialNumber",""),1L).get("version"));
    current=pool(four,db,recoveredPool);
    check(current.get("switchIp")==null&&current.get("switchPort")==null&&current.get("macAddress")==null&&current.get("serialNumber")==null,"optional information can clear");
    four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",version,"switchIp","108.56.195.251","switchPort","1:0:24","macAddress","=自由文本","serialNumber","SN-RELEASE"),1L);
    three.release(assigned,2L,"设备下线","stage14-application");
    check("CURRENT".equals(text(db,"SELECT resource_code FROM ip_release_event WHERE assignment_id="+assigned)),"release snapshots current code");
    check("108.56.195.251".equals(text(db,"SELECT switch_ip FROM ip_release_event WHERE assignment_id="+assigned))&&"SN-RELEASE".equals(text(db,"SELECT serial_number FROM ip_release_event WHERE assignment_id="+assigned)),"release snapshots switch IP and serial");
    check("=自由文本".equals(text(db,"SELECT mac_address FROM ip_release_event WHERE assignment_id="+assigned)),"release keeps the original free-form MAC");
    current=pool(four,db,recoveredPool);check(current.get("serialNumber")==null&&current.get("switchIp")==null,"released pool has no stale information");
    expect("ALLOCATION_STALE",()->four.updateAssignmentInformation(assigned,json("poolId",recoveredPool,"expectedVersion",3,"serialNumber","HISTORY"),1L));
    long reusedApp=four.submitApplication(2L,json("branchId",1,"outletId",1,"assetId",coded,"assetCode","CURRENT"));
    four.approve(reusedApp,recoveredPool,"OFFICE","",1L);
    check(pool(four,db,recoveredPool).get("serialNumber")==null&&pool(four,db,recoveredPool).get("macAddress")==null,"new allocation does not inherit old information");
    verifyReleaseExports(three,"108.56.195.251","1:0:24","SN-RELEASE","'=自由文本","CURRENT");
    verifyConcurrency(two,four,db,free.get(5),assignment(db,recoveredPool),recoveredPool,second,secondPool);
    check("ok".equals(text(db,"PRAGMA integrity_check"))&&scalar(db,"SELECT count(*) FROM pragma_foreign_key_check")==0,"integrity and foreign keys");
    System.out.println("PASS|STAGE14_INFORMATION_FREE_MAC_SERIAL_CODE_OCCUPIED_HISTORY_EXPORT_CONCURRENCY_V021_UPGRADE|"+work);
  }

  private static void verifyExports(PhaseThreeService three,String ip,String port,String serial,String mac,String code)throws Exception {
    Set<String> fields=new LinkedHashSet<>(Arrays.asList("switchIp","switchPort","macAddress","serialNumber","resourceCode"));
    for(String kind:Arrays.asList("POOL","APPLICATION","ASSIGNMENT")){
      String csv=new String(three.exportManualAware(kind,"CSV",fields,Collections.emptyMap(),1L),StandardCharsets.UTF_8);
      check(csv.contains(serial)&&csv.contains(mac)&&csv.contains(code)&&csv.contains("switchIp"),kind+" CSV fields");
      verifyXlsxRow(three.exportManualAware(kind,"XLSX",fields,Collections.emptyMap(),1L),fields,
          exportValues(ip,port,serial,mac,code),kind+" current XLSX");
      String hidden=new String(three.exportManualAware(kind,"CSV",Collections.singleton("ipAddress"),Collections.emptyMap(),1L),StandardCharsets.UTF_8);
      check(!hidden.contains(serial)&&!hidden.contains("serialNumber")&&!hidden.contains(mac),kind+" unselected fields absent");
    }
    verifyReleaseExports(three,"108.56.195.250","36","SN-MANUAL"," 设备 MAC 标签 ","MANUAL-NEW");
  }

  private static Map<String,String> exportValues(String ip,String port,String serial,String mac,String code){
    Map<String,String> values=new LinkedHashMap<>();values.put("switchIp",ip);values.put("switchPort",port);
    values.put("macAddress",mac);values.put("serialNumber",serial);values.put("resourceCode",code);return values;
  }
  private static void verifyReleaseExports(PhaseThreeService three,String ip,String port,String serial,String mac,String code)throws Exception {
    Map<String,String> values=exportValues(ip,port,serial,mac,code);Set<String> fields=values.keySet();
    String csv=new String(three.exportManualAware("RELEASE","CSV",fields,Collections.emptyMap(),1L),StandardCharsets.UTF_8);
    for(String value:values.values())check(csv.contains(value),"release CSV snapshot missing "+value);
    verifyXlsxRow(three.exportManualAware("RELEASE","XLSX",fields,Collections.emptyMap(),1L),fields,values,"release XLSX snapshot "+serial);
  }
  private static void verifyXlsxRow(byte[] bytes,Set<String> fields,Map<String,String> expected,String label)throws Exception {
    try(XSSFWorkbook wb=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
      org.apache.poi.ss.usermodel.Sheet sheet=wb.getSheetAt(0);Map<String,Integer> columns=new LinkedHashMap<>();
      check(sheet.getRow(0).getLastCellNum()==fields.size(),label+" header count");
      for(int col=0;col<fields.size();col++){
        check(sheet.getRow(0).getCell(col).getCellType()==CellType.STRING,label+" header is text");
        columns.put(sheet.getRow(0).getCell(col).getStringCellValue(),col);
      }
      check(columns.keySet().equals(fields),label+" requested header names");int matching=0;
      for(int row=1;row<=sheet.getLastRowNum();row++){
        for(int col=0;col<fields.size();col++)check(sheet.getRow(row).getCell(col).getCellType()==CellType.STRING,label+" remains plain text");
        if(!expected.get("serialNumber").equals(sheet.getRow(row).getCell(columns.get("serialNumber")).getStringCellValue()))continue;
        matching++;
        for(Map.Entry<String,String> value:expected.entrySet())check(value.getValue().equals(sheet.getRow(row).getCell(columns.get(value.getKey())).getStringCellValue()),label+" exact "+value.getKey());
      }
      check(matching==1,label+" identifies exactly one complete snapshot row");
    }
  }

  private static void verifyStatisticsCoexistence(Path db)throws Exception {
    migrate(db);seed(db);PhaseFourService four=new PhaseFourService(db);PhaseTwoService two=new PhaseTwoService(db);
    PhaseThreeService three=new PhaseThreeService(db);ManualAssignmentService manual=new ManualAssignmentService(db);
    four.createPlan(json("branchId",1,"outletId",1,"productionCidr","108.56.128.0/27"),1L);
    Map<String,Object> baseline=three.overview(null,null);long initialAvailable=n(baseline.get("availableCount")),system=n(baseline.get("systemReservedCount"));
    List<Long> free=available(db,"OFFICE");long plain=asset(four,"六状态统计设备",false);
    two.updatePoolBatch(batch("OCCUPIED",free.get(0)),1L);
    manual.register(free.get(1),json("assetId",plain),1L);
    two.updatePool(free.get(2),json("state","MANUAL_RESERVED","reservationReason","六状态统计保留"),1L);
    two.updatePool(free.get(3),json("state","DISABLED"),1L);
    Map<String,Object> overview=three.overview(null,null);Map<String,Long> expected=new LinkedHashMap<>();
    expected.put("AVAILABLE",initialAvailable-4);expected.put("ASSIGNED",1L);expected.put("OCCUPIED",1L);
    expected.put("MANUAL_RESERVED",1L);expected.put("DISABLED",1L);expected.put("SYSTEM_RESERVED",system);
    String[] counters={"availableCount","assignedCount","occupiedCount","manualReservedCount","disabledCount","systemReservedCount"};int index=0;long total=0;
    for(Map.Entry<String,Long> state:expected.entrySet()){
      check(state.getValue()>0,"all six states coexist: "+state.getKey());
      check(n(overview.get(counters[index++]))==state.getValue(),"six-state overview exact "+state.getKey());
      check(n(four.pool(null,null,null,state.getKey(),"",1).get("total"))==state.getValue(),"six-state filter exact "+state.getKey());total+=state.getValue();
    }
    check(total==n(overview.get("addressTotal"))&&total==n(baseline.get("addressTotal")),"mutually exclusive states cover every address exactly once");
    long capacity=initialAvailable-2;double rate=Math.round(2*10000D/capacity)/100D;
    check(n(overview.get("usedCount"))==2&&n(overview.get("allocatableCapacity"))==capacity&&((Number)overview.get("usageRate")).doubleValue()==rate,"usage includes assigned and occupied once, excludes reserved and disabled");
    Map<String,Object> branch=((List<Map<String,Object>>)overview.get("branches")).get(0);
    check(n(branch.get("assignedCount"))==1&&n(branch.get("occupiedCount"))==1&&n(branch.get("availableCount"))==initialAvailable-4,"branch statistics keep occupied and assigned separate");
  }

  private static void verifyConcurrency(PhaseTwoService two,PhaseFourService four,Path db,long pool,long first,long firstPool,long second,long secondPool)throws Exception {
    ExecutorService workers=Executors.newFixedThreadPool(2);
    try{
      CountDownLatch start=new CountDownLatch(1);
      List<Future<Boolean>> futures=new ArrayList<>();
      for(int i=0;i<2;i++)futures.add(workers.submit(()->{start.await();try{two.updatePoolBatch(batch("OCCUPIED",pool),1L);return true;}catch(PhaseTwoService.Problem e){if(!"IP_NOT_AVAILABLE".equals(e.code))throw e;return false;}}));
      start.countDown();int successes=0;for(Future<Boolean> f:futures)if(f.get())successes++;
      check(successes==1,"concurrent occupancy succeeds exactly once");two.updatePoolBatch(batch("AVAILABLE",pool),1L);
      CountDownLatch codeStart=new CountDownLatch(1);futures.clear();
      long firstVersion=scalar(db,"SELECT version FROM ip_assignment WHERE id="+first),secondVersion=scalar(db,"SELECT version FROM ip_assignment WHERE id="+second);
      futures.add(workers.submit(()->tryCode(four,first,firstPool,firstVersion,codeStart)));
      futures.add(workers.submit(()->tryCode(four,second,secondPool,secondVersion,codeStart)));
      codeStart.countDown();successes=0;for(Future<Boolean> f:futures)if(f.get())successes++;
      check(successes==1&&scalar(db,"SELECT count(*) FROM ip_assignment WHERE status='ASSIGNED' AND resource_code='RACE-CODE'")==1,"concurrent resource codes remain unique");
    }finally{workers.shutdownNow();}
  }
  private static boolean tryCode(PhaseFourService four,long assignment,long pool,long version,CountDownLatch start)throws Exception {
    start.await();try{four.updateAssignmentInformation(assignment,json("poolId",pool,"expectedVersion",version,"resourceCode","RACE-CODE"),1L);return true;}catch(PhaseTwoService.Problem e){if(!"RESOURCE_CODE_DUPLICATE".equals(e.code))throw e;return false;}
  }

  private static void verifyUpgrade(Path db)throws Exception {
    Object runtime=database(db);
    try(Connection c=open(db);Statement s=c.createStatement()){
      s.execute("CREATE TABLE schema_history(version TEXT PRIMARY KEY,description TEXT NOT NULL,checksum TEXT NOT NULL,installed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')))");
      List<Path> scripts=new ArrayList<>();try(java.util.stream.Stream<Path> files=Files.list(Paths.get("backend/src/main/resources/db/migration"))){files.filter(p->p.getFileName().toString().matches("V0(?:0[1-9]|1[0-9]|2[01])__.*\\.sql")).sorted().forEach(scripts::add);}
      for(Path file:scripts){String name=file.getFileName().toString(),v=name.substring(1,4);if("020".equals(v))continue;String method="apply";if("013".equals(v)||"014".equals(v))method="applyIpApplicationRebuild";else if(Arrays.asList("015","016","017").contains(v))method="applySoftDeleteReuseRebuild";Method apply=runtime.getClass().getDeclaredMethod(method,Connection.class,String.class,String.class,String.class);apply.setAccessible(true);apply.invoke(runtime,c,v,"V021 upgrade fixture","/db/migration/"+name);}
    }
    seed(db);PhaseFourService four=new PhaseFourService(db);four.createPlan(json("branchId",1,"outletId",1,"productionCidr","108.56.96.0/27"),1L);long asset=asset(four,"旧端口设备",false);
    String[] ports={"108.56.195.250-36","GE0/0/1","Eth-Trunk1","999.56.1.1-24","108.56.195.251-1:0:24"};
    try(Connection c=open(db)){
      for(int i=0;i<ports.length;i++)try(PreparedStatement p=c.prepareStatement("INSERT INTO ip_assignment(ip_pool_id,assigned_by,status,switch_port,mac_address,assignment_source,manual_asset_id,manual_asset_type,manual_asset_name,manual_requires_user,manual_requires_resource_code,manual_registered_by,manual_registered_at,source_pool_state) VALUES(?,1,?,?,'legacy MAC','MANUAL_REGISTRATION',?,'NON_TECH','旧端口设备',0,0,1,'2026-09-18','AVAILABLE')")){
        p.setLong(1,i+1);p.setString(2,i==4?"RELEASED":"ASSIGNED");p.setString(3,ports[i]);p.setLong(4,asset);p.executeUpdate();
      }
      try(Statement s=c.createStatement()){s.execute("INSERT INTO ip_release_event(assignment_id,ip_address,resource_code,switch_port,mac_address,released_by,released_by_username,released_at,branch_name,outlet_name,asset_type,result,request_id,assignment_source) VALUES(5,'108.56.96.5',NULL,'108.56.195.251-1:0:24','legacy MAC',1,'admin14','2026-09-18','阶段14支行','阶段14网点','NON_TECH','RELEASED','upgrade-history','MANUAL_REGISTRATION')");}
    }
    migrate(db);
    check(scalar(db,"SELECT count(*) FROM ip_assignment")==5&&scalar(db,"SELECT count(*) FROM ip_release_event")==1,"upgrade preserves history row counts");
    check("108.56.195.250".equals(text(db,"SELECT switch_ip FROM ip_assignment WHERE id=1"))&&"36".equals(text(db,"SELECT switch_port FROM ip_assignment WHERE id=1")),"legacy IPv4-port split");
    check(text(db,"SELECT switch_ip FROM ip_assignment WHERE id=2")==null&&"GE0/0/1".equals(text(db,"SELECT switch_port FROM ip_assignment WHERE id=2")),"legacy port-only retained");
    check("Eth-Trunk1".equals(text(db,"SELECT switch_port FROM ip_assignment WHERE id=3"))&&"999.56.1.1-24".equals(text(db,"SELECT switch_port FROM ip_assignment WHERE id=4")),"unknown legacy strings retained");
    check("108.56.195.251".equals(text(db,"SELECT switch_ip FROM ip_release_event WHERE id=1"))&&"1:0:24".equals(text(db,"SELECT switch_port FROM ip_release_event WHERE id=1")),"release legacy field split");
    check(scalar(db,"SELECT count(*) FROM ip_assignment WHERE serial_number IS NOT NULL")==0&&scalar(db,"SELECT count(*) FROM ip_address_pool WHERE is_occupied<>0")==0,"new optional fields not inferred");
    check(scalar(db,"SELECT count(*) FROM ip_assignment WHERE mac_address='legacy MAC'")==5,"upgrade preserves original MAC data");
    migrate(db);check(scalar(db,"SELECT count(*) FROM schema_history WHERE version='022'")==1,"migration idempotence");
    check("ok".equals(text(db,"PRAGMA integrity_check"))&&scalar(db,"SELECT count(*) FROM pragma_foreign_key_check")==0,"upgrade integrity");
  }

  private static Object database(Path db)throws Exception {Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");Constructor<?> constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);return constructor.newInstance(db);}
  private static void migrate(Path db)throws Exception {Object runtime=database(db);Method migrate=runtime.getClass().getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(runtime);}
  private static void seed(Path db)throws Exception {try(Connection c=open(db);Statement s=c.createStatement()){
    s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('admin14','x','管理员','ADMIN',1,0,1)");
    s.execute("INSERT INTO branch(code,name,is_active) VALUES('B14','阶段14支行',1)");s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'O14','阶段14网点',1)");
    s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin,branch_id,outlet_id) VALUES('tech14','x','科技管理员','TECH_ADMIN',1,0,0,1,1)");
  }}
  private static long asset(PhaseFourService four,String name,boolean code)throws Exception {return n(four.createAsset(json("assetType","NON_TECH","name",name,"requiresUser",0,"requiresResourceCode",code?1:0,"recommendedRangeType","OFFICE","active",1),1L).get("id"));}
  private static JsonObject json(Object...items){JsonObject out=new JsonObject();for(int i=0;i<items.length;i+=2){Object value=items[i+1];String key=String.valueOf(items[i]);if(value==null)out.add(key,JsonNull.INSTANCE);else if(value instanceof Number)out.addProperty(key,(Number)value);else out.addProperty(key,String.valueOf(value));}return out;}
  private static JsonObject batch(String state,long...ids){JsonObject out=json("state",state);JsonArray selected=new JsonArray();for(long id:ids)selected.add(id);out.add("ids",selected);return out;}
  private static List<Long> available(Path db,String type)throws Exception {try(Connection c=open(db);PreparedStatement p=c.prepareStatement("SELECT p.id FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id WHERE r.range_type=? AND p.is_active=1 AND p.is_reserved=0 AND p.is_occupied=0 ORDER BY p.id")){p.setString(1,type);try(ResultSet r=p.executeQuery()){List<Long> rows=new ArrayList<>();while(r.next())rows.add(r.getLong(1));return rows;}}}
  private static long assignment(Path db,long pool)throws Exception{return scalar(db,"SELECT id FROM ip_assignment WHERE ip_pool_id="+pool+" AND status='ASSIGNED'");}
  private static String address(Path db,long pool)throws Exception{return text(db,"SELECT ip_address FROM ip_address_pool WHERE id="+pool);}
  private static Map<String,Object> pool(PhaseFourService four,Path db,long pool)throws Exception {List<Map<String,Object>> rows=(List<Map<String,Object>>)four.pool(null,null,null,null,address(db,pool),1).get("items");check(rows.size()==1,"exact pool lookup");return rows.get(0);}
  private static long scalar(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){check(r.next(),"scalar missing");return r.getLong(1);}}
  private static String text(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){check(r.next(),"text missing");return r.getString(1);}}
  private static Connection open(Path db)throws Exception {Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;}
  private static void assertPrivate(List<Map<String,Object>> rows){for(Map<String,Object> row:rows)for(String field:Arrays.asList("switchIp","switchPort","macAddress","serialNumber"))check(!row.containsKey(field),"technology response leaked "+field);}
  private static long n(Object value){return value==null?0:((Number)value).longValue();}
  private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  private static void expect(String code,Operation action)throws Exception {try{action.run();throw new AssertionError("expected "+code);}catch(PhaseTwoService.Problem error){if(!code.equals(error.code))throw new AssertionError("expected "+code+", got "+error.code,error);}}
  private interface Operation{void run()throws Exception;}
}
