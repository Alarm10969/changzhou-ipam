package com.icbc.changzhou.ipam;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** Numeric mixed-prefix overlap, source-plan offsets, and safe suggestions. */
public final class StageTenPlanningOverlapSmokeTest {
  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("ipam-planning-overlap-"),db=work.resolve("test.db");
    Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");
    java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(Path.class);ctor.setAccessible(true);
    Object runtime=ctor.newInstance(db);
    java.lang.reflect.Method migrate=type.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(runtime);
    try(Connection c=open(db)){
      Statement s=c.createStatement();
      s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('admin','x','管理员','ADMIN',1,0,1)");
      s.execute("INSERT INTO branch(code,name,is_active) VALUES('B1','测试支行',1)");
      s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'O1','首个网点',1)");
      s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'O2','相邻网点',1)");
      s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'O3','批量网点一',1)");
      s.execute("INSERT INTO outlet(branch_id,code,name,is_active) VALUES(1,'O4','批量网点二',1)");
    }
    PhaseFourService service=new PhaseFourService(db);
    JsonObject first=plan(1,"108.56.32.0/27");
    List<?> segments=(List<?>)service.previewPlan(first).get("segments");
    Map<?,?> management=(Map<?,?>)segments.get(1);
    if(!"108.56.35.0/27".equals(management.get("cidr"))||!"108.56.35.30".equals(management.get("gateway")))throw new AssertionError("/27 management must use third-octet +3 and last usable gateway");
    service.createPlan(first,1L);
    try{service.previewPlan(first);throw new AssertionError("existing outlet preview offered an unusable suggestion");}
    catch(PhaseTwoService.Problem expected){if(!"OUTLET_NETWORK_EXISTS".equals(expected.code))throw expected;}

    JsonObject second=plan(2,"108.56.32.0/24");
    Map<String,Object> mixed=service.previewPlan(second);
    if(((List<?>)mixed.get("conflicts")).isEmpty())throw new AssertionError("/24 containment of existing /27 was not found");
    String suggestion=String.valueOf(mixed.get("suggestedProductionCidr"));
    if(!suggestion.endsWith("/24"))throw new AssertionError("suggestion must preserve the production prefix");
    if(!((List<?>)service.previewPlan(plan(2,suggestion)).get("conflicts")).isEmpty())throw new AssertionError("recommended six-network set is not free");
    try{service.createPlan(second,1L);throw new AssertionError("overlapping create was accepted");}
    catch(PhaseTwoService.Problem expected){if(!"CIDR_OVERLAP".equals(expected.code)||!expected.getMessage().contains("O1")||!expected.getMessage().contains("建议将生产网段"))throw expected;}
    second.addProperty("productionCidr","108.56.32.32/27");
    if(!((List<?>)service.previewPlan(second).get("conflicts")).isEmpty())throw new AssertionError("adjacent /27 must not require a /23 block boundary");
    service.createPlan(second,1L);
    try(Connection c=open(db)){count(c,"SELECT count(*) FROM ip_range WHERE outlet_id IN (1,2)",12);}

    byte[] batch=("branch_code,outlet_code,production_cidr\nB1,O3,108.56.80.0/27\nB1,O4,108.56.80.0/24\n").getBytes(StandardCharsets.UTF_8);
    Map<String,Object> imported=service.previewPlanImport("overlap.csv",batch,1L);
    if(!"FAILED".equals(imported.get("status"))||((Number)imported.get("errorRows")).intValue()!=2)throw new AssertionError("mixed-prefix batch overlap must reject both rows");
    List<?> errors=(List<?>)imported.get("errors");
    if(errors.size()!=2||!String.valueOf(((Map<?,?>)errors.get(0)).get("message")).contains("导入第 3 行"))throw new AssertionError("batch preview must name the conflicting row and network");
    try(Connection c=open(db)){count(c,"SELECT count(*) FROM ip_range WHERE outlet_id IN (3,4)",0);}
    String firstSuggestion=suggested((Map<?,?>)errors.get(0));
    String secondSuggestion=suggested((Map<?,?>)errors.get(1));
    if(firstSuggestion.equals(secondSuggestion))throw new AssertionError("batch recommendations must differ");
    byte[] corrected=("branch_code,outlet_code,production_cidr\nB1,O3,"+firstSuggestion+"\nB1,O4,"+secondSuggestion+"\n").getBytes(StandardCharsets.UTF_8);
    Map<String,Object> correctedPreview=service.previewPlanImport("corrected.csv",corrected,1L);
    if(!"PREVIEW_READY".equals(correctedPreview.get("status"))||((Number)correctedPreview.get("errorRows")).intValue()!=0)throw new AssertionError("applying every recommendation together must produce a valid batch");
    service.confirmPlanImport(((Number)correctedPreview.get("id")).longValue(),1L);
    try(Connection c=open(db)){count(c,"SELECT count(*) FROM ip_range WHERE outlet_id IN (3,4)",12);}
    System.out.println("PASS|STAGE10_PLAN_OVERLAP_AND_SUGGESTION|"+work);
  }
  private static String suggested(Map<?,?> error){
    Matcher match=Pattern.compile("建议将生产网段改为 ([0-9.]+/[0-9]+)").matcher(String.valueOf(error.get("message")));
    if(!match.find())throw new AssertionError("missing actionable batch recommendation: "+error.get("message"));
    return match.group(1);
  }
  private static JsonObject plan(int outlet,String production){JsonObject x=new JsonObject();x.addProperty("branchId",1);x.addProperty("outletId",outlet);x.addProperty("productionCidr",production);return x;}
  private static Connection open(Path db)throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
  private static void count(Connection c,String sql,long expected)throws Exception{try(ResultSet r=c.createStatement().executeQuery(sql)){r.next();if(r.getLong(1)!=expected)throw new AssertionError(sql+" expected="+expected+" actual="+r.getLong(1));}}
}
