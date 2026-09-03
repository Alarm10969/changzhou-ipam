package com.icbc.changzhou.ipam;

import com.google.gson.JsonObject;
import java.io.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Display-only outlet directory.  It intentionally has no connection to allocation workflows. */
final class OutletDirectoryService {
  private static final int MAX_BYTES=5*1024*1024, MAX_ROWS=10000;
  private static final String[] HEADERS={"managed_outlet_no","outlet_no","org_code","outlet_name","outlet_address","phone","branch_code"};
  private final Path dbFile;
  OutletDirectoryService(Path file){dbFile=file;}

  List<Map<String,Object>> list(String branchId,String q)throws Exception{
    StringBuilder sql=new StringBuilder("SELECT d.id,d.managed_outlet_no AS managedOutletNo,d.outlet_no AS outletNo,d.org_code AS orgCode,d.outlet_name AS outletName,d.outlet_address AS outletAddress,d.phone,d.branch_id AS branchId,b.code AS branchCode,b.name AS branchName FROM outlet_directory d JOIN branch b ON b.id=d.branch_id WHERE 1=1");
    List<Object> args=new ArrayList<>();
    if(!blank(branchId).isEmpty()){try{args.add(Long.valueOf(branchId));}catch(NumberFormatException e){throw new PhaseTwoService.Problem(400,"BRANCH_ID_INVALID","支行编号不正确。");}sql.append(" AND d.branch_id=?");}
    if(!blank(q).isEmpty()){String like="%"+blank(q)+"%";sql.append(" AND (d.managed_outlet_no LIKE ? OR d.outlet_no LIKE ? OR d.org_code LIKE ? OR d.outlet_name LIKE ? OR d.outlet_address LIKE ? OR d.phone LIKE ?)");for(int i=0;i<6;i++)args.add(like);}
    sql.append(" ORDER BY b.name,d.outlet_name,d.id");return rows(sql.toString(),args);
  }

  void update(long id,JsonObject body,long actor)throws Exception{
    String managed=req(body,"managedOutletNo",64),outlet=req(body,"outletNo",64),org=req(body,"orgCode",64),name=req(body,"outletName",100),address=optional(body,"outletAddress",200),phone=optional(body,"phone",32);long branch=number(body,"branchId");
    synchronized(PhaseTwoService.WRITE_LOCK){try(Connection c=open()){c.setAutoCommit(false);ensureActiveBranch(c,branch);if(existsOtherManaged(c,managed,id))throw new PhaseTwoService.Problem(409,"OUTLET_DIRECTORY_DUPLICATE","管理网点号已存在。");try(PreparedStatement p=c.prepareStatement("UPDATE outlet_directory SET managed_outlet_no=?,outlet_no=?,org_code=?,outlet_name=?,outlet_address=?,phone=?,branch_id=?,updated_at=? WHERE id=?")){p.setString(1,managed);p.setString(2,outlet);p.setString(3,org);p.setString(4,name);p.setString(5,address);p.setString(6,phone);p.setLong(7,branch);p.setString(8,Instant.now().toString());p.setLong(9,id);if(p.executeUpdate()!=1)throw new PhaseTwoService.Problem(404,"OUTLET_DIRECTORY_NOT_FOUND","网点信息不存在。");}audit(c,actor,"UPDATE_OUTLET_DIRECTORY",String.valueOf(id),"SUCCESS",null);c.commit();} }
  }

  byte[] template()throws Exception{
    try(XSSFWorkbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){Sheet s=wb.createSheet("网点信息导入模板");Row h=s.createRow(0),sample=s.createRow(1);String[] example={"M001","O001","ORG001","示例网点","常州市示例路 1 号","0519-12345678","B001"};for(int i=0;i<HEADERS.length;i++){h.createCell(i,CellType.STRING).setCellValue(HEADERS[i]);sample.createCell(i,CellType.STRING).setCellValue(example[i]);s.setColumnWidth(i,i==4?30*256:18*256);}wb.write(out);return out.toByteArray();}
  }

  Map<String,Object> importXlsx(String fileName,byte[] raw,long actor)throws Exception{
    if(fileName==null||!fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")||raw==null||raw.length==0||raw.length>MAX_BYTES)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_XLSX_REQUIRED","请上传不超过 5 MiB 的 .xlsx 网点信息文件。");
    List<Map<String,String>> records=parse(raw);List<Map<String,Object>> errors=new ArrayList<>();
    synchronized(PhaseTwoService.WRITE_LOCK){try(Connection c=open()){c.setAutoCommit(false);Set<String> batch=new HashSet<>();for(int i=0;i<records.size();i++){Map<String,String> r=records.get(i);String message=validate(c,r,batch);if(message!=null)errors.add(error(i+2,message));}
      if(!errors.isEmpty()){audit(c,actor,"IMPORT_OUTLET_DIRECTORY","OUTLET_DIRECTORY","FAILURE",String.valueOf(errors.size()));c.commit();return result(records.size(),0,errors);}
      try(PreparedStatement p=c.prepareStatement("INSERT INTO outlet_directory(managed_outlet_no,outlet_no,org_code,outlet_name,outlet_address,phone,branch_id) VALUES(?,?,?,?,?,?,?)")){for(Map<String,String> r:records){p.setString(1,r.get("managed_outlet_no"));p.setString(2,r.get("outlet_no"));p.setString(3,r.get("org_code"));p.setString(4,r.get("outlet_name"));p.setString(5,nullIfBlank(r.get("outlet_address")));p.setString(6,nullIfBlank(r.get("phone")));p.setLong(7,branchId(c,r.get("branch_code")));p.addBatch();}p.executeBatch();}
      audit(c,actor,"IMPORT_OUTLET_DIRECTORY","OUTLET_DIRECTORY","SUCCESS",String.valueOf(records.size()));c.commit();return result(records.size(),records.size(),errors);
    }}
  }

  private List<Map<String,String>> parse(byte[] raw)throws Exception{
    try(XSSFWorkbook wb=new XSSFWorkbook(new ByteArrayInputStream(raw))){if(wb.getNumberOfSheets()==0)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_XLSX_INVALID","Excel 文件中没有工作表。");Sheet s=wb.getSheetAt(0);Row header=s.getRow(s.getFirstRowNum());if(header==null)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_HEADER_INVALID","Excel 缺少模板表头。");DataFormatter formatter=new DataFormatter(Locale.ROOT);Map<String,Integer> columns=new LinkedHashMap<>();for(int i=0;i<header.getLastCellNum();i++){String value=cell(header.getCell(i),formatter);if(!value.isEmpty())columns.put(value,i);}for(String key:HEADERS)if(!columns.containsKey(key))throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_HEADER_INVALID","缺少模板列："+key+"。");for(String key:columns.keySet())if(!Arrays.asList(HEADERS).contains(key))throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_HEADER_INVALID","存在未知模板列："+key+"。");List<Map<String,String>> result=new ArrayList<>();for(int rowNo=header.getRowNum()+1;rowNo<=s.getLastRowNum();rowNo++){Row row=s.getRow(rowNo);Map<String,String> item=new LinkedHashMap<>();boolean nonEmpty=false;for(String key:HEADERS){String value=row==null?"":cell(row.getCell(columns.get(key)),formatter);item.put(key,value);if(!value.isEmpty())nonEmpty=true;}if(nonEmpty)result.add(item);if(result.size()>MAX_ROWS)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_TOO_MANY_ROWS","单次导入最多 10000 行。");}if(result.isEmpty())throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_EMPTY","Excel 中没有可导入的数据行。");return result;}catch(PhaseTwoService.Problem e){throw e;}catch(Exception e){throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_XLSX_INVALID","Excel 文件无法读取，请使用下载的 .xlsx 模板。");}
  }
  private String validate(Connection c,Map<String,String> r,Set<String> batch)throws Exception{
    for(String key:new String[]{"managed_outlet_no","outlet_no","org_code","outlet_name","branch_code"})if(blank(r.get(key)).isEmpty())return "“"+key+"”不能为空。";
    if(r.get("managed_outlet_no").length()>64||r.get("outlet_no").length()>64||r.get("org_code").length()>64||r.get("outlet_name").length()>100||r.get("outlet_address").length()>200||r.get("phone").length()>32)return "字段长度超过允许范围。";
    if(!batch.add(r.get("managed_outlet_no")))return "管理网点号在本次文件中重复。";if(exists(c,"SELECT 1 FROM outlet_directory WHERE managed_outlet_no=?",r.get("managed_outlet_no")))return "管理网点号已存在，请改为逐条编辑。";
    try{branchId(c,r.get("branch_code"));}catch(PhaseTwoService.Problem e){return e.getMessage();}return null;
  }
  private static Map<String,Object> error(int row,String message){Map<String,Object> x=new LinkedHashMap<>();x.put("rowNumber",row);x.put("message",message);return x;}
  private static Map<String,Object> result(int total,int inserted,List<Map<String,Object>> errors){Map<String,Object> x=new LinkedHashMap<>();x.put("totalRows",total);x.put("insertedRows",inserted);x.put("errorRows",errors.size());x.put("errors",errors);return x;}
  private long branchId(Connection c,String code)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT id FROM branch WHERE code=? AND is_active=1 AND deleted_at IS NULL")){p.setString(1,code);ResultSet r=p.executeQuery();if(!r.next())throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_BRANCH_INVALID","所属支行不存在、未启用，或已删除："+code+"。");return r.getLong(1);}}
  private static void ensureActiveBranch(Connection c,long id)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM branch WHERE id=? AND is_active=1 AND deleted_at IS NULL")){p.setLong(1,id);if(!p.executeQuery().next())throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_BRANCH_INVALID","请选择已启用的所属支行。");}}
  private static boolean exists(Connection c,String sql,String value)throws Exception{try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,value);return p.executeQuery().next();}}
  private static boolean existsOtherManaged(Connection c,String value,long id)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM outlet_directory WHERE managed_outlet_no=? AND id<>?")){p.setString(1,value);p.setLong(2,id);return p.executeQuery().next();}}
  private List<Map<String,Object>> rows(String sql,List<Object> values)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<values.size();i++){Object v=values.get(i);if(v instanceof Long)p.setLong(i+1,(Long)v);else p.setString(i+1,String.valueOf(v));}try(ResultSet r=p.executeQuery()){List<Map<String,Object>> out=new ArrayList<>();ResultSetMetaData meta=r.getMetaData();while(r.next()){Map<String,Object> item=new LinkedHashMap<>();for(int i=1;i<=meta.getColumnCount();i++)item.put(meta.getColumnLabel(i),r.getObject(i));out.add(item);}return out;}}}
  private Connection open()throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+dbFile);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;}
  private static void audit(Connection c,long actor,String action,String target,String result,String detail)throws Exception{try(PreparedStatement p=c.prepareStatement("INSERT INTO audit_log(actor_user_id,action,target_type,target_id,result,detail_json) VALUES(?,?,?,?,?,?)")){p.setLong(1,actor);p.setString(2,action);p.setString(3,"OUTLET_DIRECTORY");p.setString(4,target);p.setString(5,result);p.setString(6,detail);p.executeUpdate();}}
  private static String req(JsonObject b,String key,int max){String value=optional(b,key,max);if(value==null)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_REQUIRED","请填写："+key+"。");return value;}
  private static String optional(JsonObject b,String key,int max){if(!b.has(key)||b.get(key).isJsonNull())return null;String value=blank(b.get(key).getAsString());if(value.length()>max)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_LENGTH","字段过长："+key+"。");return value.isEmpty()?null:value;}
  private static long number(JsonObject b,String key){try{if(!b.has(key))throw new Exception();return b.get(key).getAsLong();}catch(Exception e){throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_BRANCH_INVALID","所属支行不正确。");}}
  private static String blank(String value){return value==null?"":value.trim();}
  private static String nullIfBlank(String value){String x=blank(value);return x.isEmpty()?null:x;}
  private static String cell(Cell c,DataFormatter f){return c==null?"":f.formatCellValue(c).trim();}
}
