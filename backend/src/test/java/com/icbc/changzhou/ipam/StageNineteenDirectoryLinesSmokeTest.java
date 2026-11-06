package com.icbc.changzhou.ipam;
import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.lang.reflect.*;
import java.io.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
public class StageNineteenDirectoryLinesSmokeTest {
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  static byte[] sheet(String[] headers,String[]...values)throws Exception {
    try(XSSFWorkbook w=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      Sheet s=w.createSheet();Row h=s.createRow(0);for(int i=0;i<headers.length;i++)h.createCell(i).setCellValue(headers[i]);
      for(int j=0;j<values.length;j++){Row r=s.createRow(j+1);for(int i=0;i<values[j].length;i++)r.createCell(i).setCellValue(values[j][i]);}
      w.write(out);return out.toByteArray();
    }
  }
  static void fail(RunnableThrow call)throws Exception{try{call.run();throw new AssertionError("should fail");}catch(PhaseTwoService.Problem expected){}}
  interface RunnableThrow{void run()throws Exception;}
  public static void main(String[] args)throws Exception {
    Path file=Files.createTempDirectory("ipam-directory19").resolve("db");
    Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");
    Constructor<?> constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
    Object database=constructor.newInstance(file);Method migrate=type.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(database);
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);Statement s=c.createStatement()){
      s.execute("INSERT INTO sys_user(id,username,password_hash,real_name,role) VALUES(1,'adm19','x','a','ADMIN'),(2,'adm20','x','b','ADMIN')");
      s.execute("INSERT INTO branch(id,code,name) VALUES(1,'B1','测试支行'),(2,'B2','另一支行')");
    }
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);Statement sql=c.createStatement()) {
      sql.execute("INSERT INTO outlet_directory(outlet_no,org_code,outlet_name,branch_id) VALUES('OLD','OLDORG','升级前网点',1)");
      for(String column:OutletDirectoryService.LINES)sql.execute("ALTER TABLE outlet_directory DROP COLUMN "+column);
      sql.execute("DROP TABLE directory_import_job");
      sql.execute("DELETE FROM schema_history WHERE version='024'");
    }
    migrate.invoke(database);
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);Statement sql=c.createStatement()) {
      try(ResultSet r=sql.executeQuery("SELECT outlet_name,line1_fee FROM outlet_directory WHERE outlet_no='OLD'")) {
        check(r.next()&&r.getString(1).equals("升级前网点")&&r.getString(2)==null,"upgrade lost directory");
      }
      sql.execute("DELETE FROM outlet_directory WHERE outlet_no='OLD'");
    }
    OutletDirectoryService service=new OutletDirectoryService(file);
    String[] full={"branch_code","outlet_no","org_code","outlet_name","line1_fee","line2_fee","line1_bandwidth","line1_carrier","flat_status"};
    Path fixtures=Paths.get("outputs/v031-local/test-data");Files.createDirectories(fixtures);
    Files.write(fixtures.resolve("directory.xlsx"),sheet(full,new String[]{"B1","001","ORG","测试双线网点","1150元/月","0","动态","联通","扁平化"}));
    Map<String,Object> p=service.preview("t.xlsx",sheet(full,new String[]{"B1","001","ORG","网点","1150元/月","0","动态","联通","扁平化"}),1);
    check(service.list(null,null).isEmpty(),"preview wrote records");
    String id=(String)p.get("id");fail(()->service.confirm(id,2));
    service.confirm(id,1);service.confirm(id,1);
    Map<String,Object> row=service.list(null,null).get(0);
    check("1150.00".equals(row.get("line1_fee"))&&"0.00".equals(row.get("line2_fee")),"money exact");
    check(service.list(null,null).size()==1,"duplicate confirmation wrote twice");
    String[] small={"branch_code","outlet_no","line1_fee","line2_carrier"};
    p=service.preview("t.xlsx",sheet(small,new String[]{"B1","001","","移动"}),1);service.confirm((String)p.get("id"),1);
    check("1150.00".equals(service.list(null,null).get(0).get("line1_fee")),"blank overwrote fee");
    p=service.preview("t.xlsx",sheet(small,new String[]{"B1","001","900",""},new String[]{"B2","001","800",""}),1);
    check(((Number)p.get("errorRows")).intValue()==1,"unmatched supplement accepted");
    String bad=(String)p.get("id");fail(()->service.confirm(bad,1));
    check("1150.00".equals(service.list(null,null).get(0).get("line1_fee")),"partial write");
    p=service.preview("t.xlsx",sheet(small,new String[]{"B1","001","900",""}),1);
    String stale=(String)p.get("id");
    JsonObject edit=new JsonObject();
    edit.addProperty("outletNo","001");edit.addProperty("orgCode","ORG");edit.addProperty("outletName","网点");
    edit.addProperty("branchId",1);edit.addProperty("line1_fee","");
    service.update(((Number)row.get("id")).longValue(),edit,1);
    check(service.list(null,null).get(0).get("line1_fee")==null,"explicit clearing failed");
    fail(()->service.confirm(stale,1));
    for(String fee:new String[]{"-1","1.001","NaN","1e3","1000000000"}) {
      Map<String,Object> result=service.preview("t.xlsx",sheet(small,new String[]{"B1","001",fee,""}),1);
      check(((Number)result.get("errorRows")).intValue()==1,"invalid fee "+fee);
    }
    p=service.preview("t.xlsx",sheet(small,new String[]{"B1","001","3",""},new String[]{"B1","001","4",""}),1);
    check(((Number)p.get("errorRows")).intValue()==1,"duplicate input");
    String[] old={"branch_code","outlet_no","org_code","outlet_name","outlet_address","phone"};
    p=service.preview("t.xlsx",sheet(old,new String[]{"B2","002","ORG2","旧模板新网点","",""}),1);
    service.confirm((String)p.get("id"),1);
    check(service.list(null,null).size()==2,"old template failed");
    check(service.template(true).length>0&&service.template().length>0,"templates");
    System.out.println("PASS|STAGE19_DIRECTORY|preview,confirm,actor,retry,blank,zero,clear,atomic,stale,fees,duplicates,old-template");
  }
}
