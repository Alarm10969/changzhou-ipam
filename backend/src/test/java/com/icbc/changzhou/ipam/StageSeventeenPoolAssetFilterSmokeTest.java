package com.icbc.changzhou.ipam;

import com.google.gson.JsonObject;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Current-assignment asset filters across approval, individual and batch registrations. */
public final class StageSeventeenPoolAssetFilterSmokeTest {
  private static final String[] HEADERS={"IP地址","资产类型","资产名称","支行名称（核对用）","网点名称（核对用）","资源编码","使用人姓名","统一认证号","负责科技管理员账号","交换机IP","交换机端口","MAC地址","设备序列号","补录说明"};

  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("ipam-stage17-pool-asset-filter-"),db=work.resolve("filter.db");
    migrate(db);seed(db);
    PhaseFourService four=new PhaseFourService(db);
    ManualAssignmentService manual=new ManualAssignmentService(db);
    AssignmentImportService imports=new AssignmentImportService(db);
    PhaseThreeService three=new PhaseThreeService(db);
    four.createPlan(json("branchId",1,"outletId",1,"productionCidr","108.56.32.0/24"),1);
    long tech=asset(four,"TECH","同名设备"),nonTech=asset(four,"NON_TECH","同名设备"),other=asset(four,"NON_TECH","其他设备");
    long inactive=asset(four,"NON_TECH","停用设备"),deleted=asset(four,"NON_TECH","删除设备");
    List<Long> pools=available(db);
    check(pools.size()>65,"fixture needs more than one page of usable IPs");
    long beforeTotal=n(four.pool(null,null,null,null,null,1).get("total"));

    long application=four.submitApplication(2,json("branchId",1,"outletId",1,"assetId",tech));
    four.approve(application,pools.get(0),"OFFICE","按资产小类批准",1);
    manual.register(pools.get(1),json("assetId",tech),1);
    Map<String,Object> preview=imports.preview("资产筛选回归.csv",csv(db,pools.get(2),pools.get(3)),1);
    check(n(preview.get("readyRows"))==2,"batch fixture did not pass preview");
    Map<String,Object> imported=imports.confirm(n(preview.get("id")),1,(int)n(preview.get("previewVersion")),true);
    check(n(imported.get("importedRows"))==2,"batch fixture not imported");
    manual.register(pools.get(4),json("assetId",other),1);
    manual.register(pools.get(5),json("assetId",inactive),1);
    manual.register(pools.get(6),json("assetId",deleted),1);
    four.updateAsset(inactive,json("assetType","TECH","name","已改类停用设备","requiresUser",0,"requiresResourceCode",0,"recommendedRangeType","OFFICE","active",0),1);
    four.deleteAsset(deleted,1);

    Set<Long> techPools=new LinkedHashSet<>();
    techPools.addAll(pools.subList(0,3));
    for(int i=10;i<65;i++){manual.register(pools.get(i),json("assetId",tech),1);techPools.add(pools.get(i));}
    check(techPools.size()==58,"pagination fixture size");
    check(ids(four,"TECH",null).equals(techPools),"asset type must cover approval, manual and batch assignments");
    check(ids(four,null,tech).equals(techPools),"asset ID without major category must cover all current assignments of that definition");
    check(ids(four,"TECH",tech).equals(techPools),"combined major/minor category mismatch");
    check(ids(four,null,nonTech).equals(Collections.singleton(pools.get(3))),"same-name definitions across categories must remain distinct");
    check(n(filter(four,null,null,null,null,"NON_TECH",null,null,1).get("total"))==4,"non-tech category must include all its minor categories");
    check(n(filter(four,null,null,null,null,"TECH",nonTech,null,1).get("total"))==0,"mismatched category and definition must return no result");
    check(ids(four,"NON_TECH",inactive).equals(Collections.singleton(pools.get(5))),"disabled definition must not hide its current assignment");
    check(ids(four,null,inactive).equals(Collections.singleton(pools.get(5))),"definition changes must preserve ID-only filtering");
    check(ids(four,"TECH",inactive).isEmpty(),"definition category changes must not rewrite the current allocation snapshot");
    check(ids(four,"NON_TECH",deleted).equals(Collections.singleton(pools.get(6))),"soft-deleted definition must not hide its current assignment");

    Map<String,Object> page1=filter(four,null,null,null,null,"TECH",tech,null,1),page2=filter(four,null,null,null,null,"TECH",tech,null,2),page3=filter(four,null,null,null,null,"TECH",tech,null,3);
    check(n(page1.get("total"))==58&&n(page2.get("total"))==58&&n(page3.get("total"))==58,"every page must expose the filtered count");
    check(items(page1).size()==50&&items(page2).size()==8&&items(page3).isEmpty(),"filtered page boundaries");
    Set<Long> pages=rowIds(page1);pages.addAll(rowIds(page2));check(pages.equals(techPools),"pagination omitted or duplicated a matching IP");

    Map<String,Object> combined=filter(four,1L,1L,"OFFICE","ASSIGNED","TECH",tech,"同名设备",1);
    check(n(combined.get("total"))==58,"asset filters must compose with branch/outlet/range/state/keyword");
    String approvedIp=text(db,"SELECT ip_address FROM ip_address_pool WHERE id="+pools.get(0));
    Map<String,Object> exact=filter(four,1L,1L,"OFFICE","ASSIGNED","TECH",tech,approvedIp,1);
    check(n(exact.get("total"))==1&&rowIds(exact).contains(pools.get(0)),"exact IP search must compose with asset filters");
    check(n(filter(four,2L,null,null,null,"TECH",tech,null,1).get("total"))==0,"branch filter must compose");
    check(n(filter(four,null,2L,null,null,"TECH",tech,null,1).get("total"))==0,"outlet filter must compose");
    check(n(filter(four,null,null,"PRODUCTION",null,"TECH",tech,null,1).get("total"))==0,"range filter must compose");
    check(n(filter(four,null,null,null,"AVAILABLE","TECH",tech,null,1).get("total"))==0,"unassigned IPs must not acquire an asset type");
    check(n(filter(four,null,null,null,null,null,Long.MAX_VALUE,null,1).get("total"))==0,"unknown positive definition should have no matches");
    check(n(filter(four,null,null,null,null," ",null,null,1).get("total"))==beforeTotal,"blank asset category must retain the unfiltered pool");
    check(n(four.pool(null,null,null,"",1).get("total"))==beforeTotal,"legacy overload must retain unfiltered behavior");

    for(int i=0;i<3;i++){
      three.forceRelease(assignment(db,pools.get(i)),1,"资产筛选释放回归","stage17-release-"+i);
      techPools.remove(pools.get(i));
    }
    check(ids(four,"TECH",tech).equals(techPools),"released approval/manual/batch history must not participate in asset filters");
    check(n(filter(four,null,null,null,null,"TECH",null,approvedIp,1).get("total"))==0,"released approval snapshot leaked through IP search");
    Map<String,Object> released=filter(four,null,null,null,"AVAILABLE",null,null,approvedIp,1);
    check(n(released.get("total"))==1,"released IP must remain visible without asset filters");
    manual.register(pools.get(0),json("assetId",other),1);
    check(n(filter(four,null,null,null,null,"TECH",tech,approvedIp,1).get("total"))==0,"reassignment must not reuse the released asset classification");
    check(n(filter(four,null,null,null,null,"NON_TECH",other,approvedIp,1).get("total"))==1,"reassignment must use its new current asset classification");
    check(n(filter(four,null,null,null,null,null,null,null,1).get("total"))==beforeTotal,"history must not duplicate unfiltered IP rows");

    expect("ASSET_TYPE_INVALID",()->filter(four,null,null,null,null,"OTHER",null,null,1));
    expect("ASSET_TYPE_INVALID",()->filter(four,null,null,null,null,"tech",null,null,1));
    expect("ASSET_ID_INVALID",()->filter(four,null,null,null,null,null,0L,null,1));
    expect("ASSET_ID_INVALID",()->filter(four,null,null,null,null,null,-1L,null,1));
    verifyOptions(four,manual,three,db,pools,inactive,deleted);
    check("ok".equals(text(db,"PRAGMA integrity_check"))&&scalar(db,"SELECT count(*) FROM pragma_foreign_key_check")==0,"fixture integrity");
    System.out.println("PASS|STAGE17_POOL_ASSET_FILTER|major,minor,approval,manual,batch,same-name,inactive,deleted,release,reassignment,combined,pagination,validation,snapshot-options|"+work);
  }

  private static void verifyOptions(PhaseFourService four,ManualAssignmentService manual,PhaseThreeService three,Path db,List<Long> pools,long inactive,long deleted)throws Exception{
    List<Map<String,Object>> options=four.poolAssetOptions();Set<String> distinct=new HashSet<>();
    for(Map<String,Object> row:options)check(distinct.add(row.get("id")+"/"+row.get("assetType")+"/"+row.get("name")),"duplicate asset-option snapshot tuple");
    Map<String,Object> old=option(options,inactive,"NON_TECH","停用设备"),current=option(options,inactive,"TECH","已改类停用设备");
    check(old!=null&&current!=null&&n(old.get("active"))==0&&n(current.get("active"))==0,"changed/disabled definition must keep both current and still-used snapshot options");
    Map<String,Object> deletedOption=option(options,deleted,"NON_TECH","删除设备");
    check(deletedOption!=null&&deletedOption.get("deletedAt")!=null,"deleted definition snapshot option must retain its deletion marker");

    long approvalAsset=asset(four,"TECH","审批改名前");
    long application=four.submitApplication(2,json("branchId",1,"outletId",1,"assetId",approvalAsset));
    four.approve(application,pools.get(7),"OFFICE","审批选项快照回归",1);
    four.updateAsset(approvalAsset,json("assetType","NON_TECH","name","审批改名后","requiresUser",0,"requiresResourceCode",0,"recommendedRangeType","OFFICE","active",0),1);
    check(option(four.poolAssetOptions(),approvalAsset,"TECH","审批改名前")!=null,"approval-only allocation snapshot missing from options");
    check(option(four.poolAssetOptions(),approvalAsset,"NON_TECH","审批改名后")!=null,"unused current disabled definition missing from options");

    three.forceRelease(assignment(db,pools.get(5)),1,"选项释放停用资产","stage17-options-inactive");
    three.forceRelease(assignment(db,pools.get(6)),1,"选项释放删除资产","stage17-options-deleted");
    three.forceRelease(assignment(db,pools.get(7)),1,"选项释放审批资产","stage17-options-approved");
    options=four.poolAssetOptions();
    check(option(options,inactive,"NON_TECH","停用设备")==null,"released manual snapshot remained selectable");
    check(option(options,inactive,"TECH","已改类停用设备")!=null,"current disabled definition disappeared after release");
    check(option(options,deleted,"NON_TECH","删除设备")==null,"deleted definition without a current assignment remained selectable");
    check(option(options,approvalAsset,"TECH","审批改名前")==null,"released approval snapshot remained selectable");
    check(option(options,approvalAsset,"NON_TECH","审批改名后")!=null,"current definition disappeared after approval release");

    long hidden=asset(four,"NON_TECH","可见上下文设备");manual.register(pools.get(8),json("assetId",hidden),1);four.deleteAsset(hidden,1);
    long range=scalar(db,"SELECT ip_range_id FROM ip_address_pool WHERE id="+pools.get(8));
    String[] contexts={"ip_address_pool WHERE id="+pools.get(8),"ip_range WHERE id="+range,"branch WHERE id=1","outlet WHERE id=1"};
    for(String context:contexts){
      check(option(four.poolAssetOptions(),hidden,"NON_TECH","可见上下文设备")!=null,"visible assignment option missing before context test");
      String[] target=context.split(" WHERE ",2);change(db,"UPDATE "+target[0]+" SET deleted_at='2026-10-28T00:00:00Z' WHERE "+target[1]);
      check(option(four.poolAssetOptions(),hidden,"NON_TECH","可见上下文设备")==null,"deleted pool context leaked a snapshot option: "+target[0]);
      change(db,"UPDATE "+target[0]+" SET deleted_at=NULL WHERE "+target[1]);
    }
  }
  private static Map<String,Object> option(List<Map<String,Object>> options,long id,String type,String name){for(Map<String,Object> row:options)if(n(row.get("id"))==id&&type.equals(row.get("assetType"))&&name.equals(row.get("name")))return row;return null;}
  private static Map<String,Object> filter(PhaseFourService service,Long branch,Long outlet,String range,String state,String assetType,Long assetId,String term,int page)throws Exception{return service.pool(branch,outlet,range,state,assetType,assetId,term,page);}
  private static Set<Long> ids(PhaseFourService service,String type,Long assetId)throws Exception{
    Map<String,Object> first=filter(service,null,null,null,null,type,assetId,null,1);Set<Long> result=rowIds(first);
    int pages=(int)((n(first.get("total"))+49)/50);
    for(int page=2;page<=pages;page++)for(Long id:rowIds(filter(service,null,null,null,null,type,assetId,null,page)))check(result.add(id),"duplicate filtered row across pages");
    check(result.size()==n(first.get("total")),"filtered items/count mismatch");return result;
  }
  @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> result){return (List<Map<String,Object>>)result.get("items");}
  private static Set<Long> rowIds(Map<String,Object> result){Set<Long> ids=new LinkedHashSet<>();for(Map<String,Object> row:items(result))check(ids.add(n(row.get("id"))),"duplicate IP within page");return ids;}
  private static long asset(PhaseFourService service,String type,String name)throws Exception{return n(service.createAsset(json("assetType",type,"name",name,"requiresUser",0,"requiresResourceCode",0,"recommendedRangeType","OFFICE","active",1),1).get("id"));}
  private static List<Long> available(Path db)throws Exception {List<Long> result=new ArrayList<>();try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT p.id FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id WHERE r.range_type='OFFICE' AND p.is_active=1 AND p.is_reserved=0 AND p.ip_address<>r.gateway ORDER BY p.id")){while(r.next())result.add(r.getLong(1));}return result;}
  private static byte[] csv(Path db,long techPool,long nonTechPool)throws Exception {StringBuilder out=new StringBuilder("\uFEFF");csvRow(out,HEADERS);for(int i=0;i<2;i++){String[] row=new String[HEADERS.length];Arrays.fill(row,"");row[0]=text(db,"SELECT ip_address FROM ip_address_pool WHERE id="+(i==0?techPool:nonTechPool));row[1]=i==0?"TECH":"NON_TECH";row[2]="同名设备";csvRow(out,row);}return out.toString().getBytes(StandardCharsets.UTF_8);}
  private static void csvRow(StringBuilder out,String[] cells){for(int i=0;i<cells.length;i++){if(i>0)out.append(',');out.append('"').append(cells[i].replace("\"","\"\"")).append('"');}out.append("\r\n");}
  private static void migrate(Path db)throws Exception {Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");Constructor<?> constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);Object database=constructor.newInstance(db);Method migrate=type.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(database);}
  private static void seed(Path db)throws Exception {try(Connection c=open(db);Statement s=c.createStatement()){
    s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('admin17','x','管理员','ADMIN',1,0,1),('tech17','x','科技管理员','TECH_ADMIN',1,0,0)");
    s.execute("INSERT INTO branch(code,name,is_active) VALUES('B17','阶段17支行',1),('B17OTHER','另一个支行',1)");
    s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'O17','阶段17网点',1),(2,'O17OTHER','另一个网点',1)");
  }}
  private static long assignment(Path db,long pool)throws Exception{return scalar(db,"SELECT id FROM ip_assignment WHERE status='ASSIGNED' AND ip_pool_id="+pool);}
  private static Connection open(Path db)throws Exception {Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;}
  private static void change(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement()){s.executeUpdate(sql);}}
  private static long scalar(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){check(r.next(),"missing scalar");return r.getLong(1);}}
  private static String text(Path db,String sql)throws Exception {try(Connection c=open(db);Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){check(r.next(),"missing text");return r.getString(1);}}
  private static JsonObject json(Object...values){JsonObject result=new JsonObject();for(int i=0;i<values.length;i+=2){Object value=values[i+1];String key=String.valueOf(values[i]);if(value instanceof Number)result.addProperty(key,(Number)value);else result.addProperty(key,String.valueOf(value));}return result;}
  private static long n(Object value){return value==null?0:((Number)value).longValue();}
  private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  private static void expect(String code,Operation operation)throws Exception {try{operation.run();throw new AssertionError("expected "+code);}catch(PhaseTwoService.Problem error){check(code.equals(error.code),"expected "+code+", got "+error.code);}}
  private interface Operation{void run()throws Exception;}
}
