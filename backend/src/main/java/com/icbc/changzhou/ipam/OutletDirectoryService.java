package com.icbc.changzhou.ipam;

import com.google.gson.*;
import java.math.BigDecimal;
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
  private static final String[] HEADERS={"outlet_no","org_code","outlet_name","outlet_address","phone","branch_code"};
  static final String[] LINES={"flat_status","line1_carrier","line1_bandwidth","line1_fee","line2_carrier","line2_bandwidth","line2_fee"};
  private final Gson gson = new Gson();
  private final Path dbFile;
  OutletDirectoryService(Path file){dbFile=file;}

  List<Map<String,Object>> list(String branchId,String q)throws Exception{
    StringBuilder sql=new StringBuilder("SELECT d.flat_status,d.line1_carrier,d.line1_bandwidth,d.line1_fee,d.line2_carrier,d.line2_bandwidth,d.line2_fee,d.id,d.outlet_no AS outletNo,d.org_code AS orgCode,d.outlet_name AS outletName,d.outlet_address AS outletAddress,d.phone,d.branch_id AS branchId,b.code AS branchCode,b.name AS branchName FROM outlet_directory d JOIN branch b ON b.id=d.branch_id WHERE 1=1");
    List<Object> args=new ArrayList<>();
    if(!blank(branchId).isEmpty()){try{args.add(Long.valueOf(branchId));}catch(NumberFormatException e){throw new PhaseTwoService.Problem(400,"BRANCH_ID_INVALID","支行编号不正确。");}sql.append(" AND d.branch_id=?");}
    if(!blank(q).isEmpty()){String like="%"+blank(q)+"%";sql.append(" AND (d.outlet_no LIKE ? OR d.org_code LIKE ? OR d.outlet_name LIKE ? OR d.outlet_address LIKE ? OR d.phone LIKE ?)");for(int i=0;i<5;i++)args.add(like);}
    sql.append(" ORDER BY b.name,d.outlet_name,d.id");return rows(sql.toString(),args);
  }

  void update(long id,JsonObject body,long actor)throws Exception{
    String outlet=req(body,"outletNo",64),org=req(body,"orgCode",64),name=req(body,"outletName",100),address=optional(body,"outletAddress",200),phone=optional(body,"phone",32);long branch=number(body,"branchId");
    synchronized(PhaseTwoService.WRITE_LOCK){try(Connection c=open()){c.setAutoCommit(false);ensureActiveBranch(c,branch);if(existsOtherOutlet(c,outlet,branch,id))throw new PhaseTwoService.Problem(409,"OUTLET_DIRECTORY_DUPLICATE","该支行下的网点号已存在。");try(PreparedStatement p=c.prepareStatement("UPDATE outlet_directory SET outlet_no=?,org_code=?,outlet_name=?,outlet_address=?,phone=?,branch_id=?,updated_at=? WHERE id=?")){p.setString(1,outlet);p.setString(2,org);p.setString(3,name);p.setString(4,address);p.setString(5,phone);p.setLong(6,branch);p.setString(7,Instant.now().toString());p.setLong(8,id);if(p.executeUpdate()!=1)throw new PhaseTwoService.Problem(404,"OUTLET_DIRECTORY_NOT_FOUND","网点信息不存在。");}saveLines(c,id,body);audit(c,actor,"UPDATE_OUTLET_DIRECTORY",String.valueOf(id),"SUCCESS",null);c.commit();} }
  }

  byte[] template()throws Exception{return template(false);}
  byte[] template(boolean linesOnly)throws Exception{
    try(XSSFWorkbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){List<String> headers=new ArrayList<>(Arrays.asList(linesOnly?new String[]{"branch_code","outlet_no"}:HEADERS));headers.addAll(Arrays.asList(LINES));
      Sheet template=wb.createSheet("网点信息模板");Row header=template.createRow(0);
      for(int i=0;i<headers.size();i++){header.createCell(i).setCellValue(headers.get(i));template.setColumnWidth(i,22*256);}
      Sheet help=wb.createSheet("填写说明");
      String[] notes={"branch_code 所属支行编码；outlet_no 网点号，均按文本填写。","org_code 机构编码；outlet_name 网点名称，新建时必填。","outlet_address 地址；phone 电话。","flat_status 填扁平化、非扁平化或待确认。","line1_carrier / line2_carrier 两条线路运营商。","line1_bandwidth / line2_bandwidth 带宽，例如10M或动态。","line1_fee / line2_fee 月费，单位元，可填1150或1150元/月，最多两位小数。","已有网点空白保留原值，清空请到页面编辑。仅补充模板要求网点已经存在。","先上传预览，再确认整批写入；错误行需修正后重新预览。"};
      for(int i=0;i<notes.length;i++)help.createRow(i).createCell(0).setCellValue(notes[i]);help.setColumnWidth(0,110*256);wb.write(out);return out.toByteArray();}
  }

  Map<String,Object> importXlsx(String fileName,byte[] raw,long actor)throws Exception{
    if(fileName==null||!fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")||raw==null||raw.length==0||raw.length>MAX_BYTES)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_XLSX_REQUIRED","请上传不超过 5 MiB 的 .xlsx 网点信息文件。");
    List<Map<String,String>> records=parse(raw);List<Map<String,Object>> errors=new ArrayList<>();
    synchronized(PhaseTwoService.WRITE_LOCK){try(Connection c=open()){c.setAutoCommit(false);Set<String> batch=new HashSet<>();for(int i=0;i<records.size();i++){Map<String,String> r=records.get(i);String message=validate(c,r,batch);if(message==null)try{for(String key:LINES)lineValue(key,r.get(key));}catch(PhaseTwoService.Problem error){message=error.getMessage();}if(message!=null)errors.add(error(i+2,message));}
      if(!errors.isEmpty()){audit(c,actor,"IMPORT_OUTLET_DIRECTORY","OUTLET_DIRECTORY","FAILURE",String.valueOf(errors.size()));c.commit();return result(records.size(),0,errors);}
      try(PreparedStatement p=c.prepareStatement("INSERT INTO outlet_directory(outlet_no,org_code,outlet_name,outlet_address,phone,branch_id) VALUES(?,?,?,?,?,?)")){for(Map<String,String> r:records){p.setString(1,r.get("outlet_no"));p.setString(2,r.get("org_code"));p.setString(3,r.get("outlet_name"));p.setString(4,nullIfBlank(r.get("outlet_address")));p.setString(5,nullIfBlank(r.get("phone")));p.setLong(6,branchId(c,r.get("branch_code")));p.addBatch();}p.executeBatch();}
      for(Map<String,String> record:records) {
        try(PreparedStatement p=c.prepareStatement("SELECT id FROM outlet_directory WHERE branch_id=? AND outlet_no=?")){
          p.setLong(1,branchId(c,record.get("branch_code")));p.setString(2,record.get("outlet_no"));
          try(ResultSet r=p.executeQuery()){r.next();saveLines(c,r.getLong(1),gson.toJsonTree(record).getAsJsonObject());}
        }
      }
      audit(c,actor,"IMPORT_OUTLET_DIRECTORY","OUTLET_DIRECTORY","SUCCESS",String.valueOf(records.size()));c.commit();return result(records.size(),records.size(),errors);
    }}
  }

  private List<Map<String,String>> parse(byte[] raw)throws Exception{
    try(XSSFWorkbook wb=new XSSFWorkbook(new ByteArrayInputStream(raw))){if(wb.getNumberOfSheets()==0)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_XLSX_INVALID","Excel 文件中没有工作表。");Sheet s=wb.getSheetAt(0);Row header=s.getRow(s.getFirstRowNum());if(header==null)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_HEADER_INVALID","Excel 缺少模板表头。");DataFormatter formatter=new DataFormatter(Locale.ROOT);Map<String,Integer> columns=new LinkedHashMap<>();for(int i=0;i<header.getLastCellNum();i++){String value=cell(header.getCell(i),formatter);if(!value.isEmpty()){if(columns.containsKey(value))throw new PhaseTwoService.Problem(400,"DUPLICATE_HEADER","重复模板列："+value);columns.put(value,i);}}for(String key:new String[]{"branch_code","outlet_no"})if(!columns.containsKey(key))throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_HEADER_INVALID","缺少模板列："+key+"。");for(String key:columns.keySet())if(!Arrays.asList(HEADERS).contains(key)&&!Arrays.asList(LINES).contains(key))throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_HEADER_INVALID","存在未知模板列："+key+"。");List<Map<String,String>> result=new ArrayList<>();for(int rowNo=header.getRowNum()+1;rowNo<=s.getLastRowNum();rowNo++){Row row=s.getRow(rowNo);Map<String,String> item=new LinkedHashMap<>();boolean nonEmpty=false;List<String> keys=new ArrayList<>(Arrays.asList(HEADERS));keys.addAll(Arrays.asList(LINES));for(String key:keys){String value=row==null||!columns.containsKey(key)?"":cell(row.getCell(columns.get(key)),formatter);item.put(key,value);if(!value.isEmpty())nonEmpty=true;}if(nonEmpty){item.put("_row",String.valueOf(rowNo+1));result.add(item);}if(result.size()>MAX_ROWS)throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_TOO_MANY_ROWS","单次导入最多 10000 行。");}if(result.isEmpty())throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_EMPTY","Excel 中没有可导入的数据行。");return result;}catch(PhaseTwoService.Problem e){throw e;}catch(Exception e){throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_XLSX_INVALID","Excel 文件无法读取，请使用下载的 .xlsx 模板。");}
  }
  private String validate(Connection c,Map<String,String> r,Set<String> batch)throws Exception{
    for(String key:new String[]{"outlet_no","org_code","outlet_name","branch_code"})if(blank(r.get(key)).isEmpty())return "“"+key+"”不能为空。";
    if(r.get("outlet_no").length()>64||r.get("org_code").length()>64||r.get("outlet_name").length()>100||r.get("outlet_address").length()>200||r.get("phone").length()>32)return "字段长度超过允许范围。";
    long branch;try{branch=branchId(c,r.get("branch_code"));}catch(PhaseTwoService.Problem e){return e.getMessage();}String key=branch+"|"+r.get("outlet_no");if(!batch.add(key))return "同一支行下的网点号在本次文件中重复。";if(existsOtherOutlet(c,r.get("outlet_no"),branch,0))return "该支行下的网点号已存在，请改为逐条编辑。";
    return null;
  }
  private static String lineValue(String key,String raw) {
    String value=blank(raw);
    if(value.isEmpty()) return null;
    if(key.equals("flat_status")) {
      if(!Arrays.asList("扁平化","非扁平化","待确认").contains(value))
        throw new PhaseTwoService.Problem(400,"LINE_INVALID","扁平化状态须为扁平化、非扁平化或待确认。");
    } else if(key.endsWith("_fee")) {
      value=value.replaceFirst("元/月$","").trim();
      if(!value.matches("[0-9]{1,9}(\\.[0-9]{1,2})?"))
        throw new PhaseTwoService.Problem(400,"LINE_INVALID","月费须为非负金额，最多9位整数和2位小数。");
      value=new BigDecimal(value).setScale(2).toPlainString();
    } else if(value.length()>100 || value.matches("(?s).*[\\p{Cntrl}].*"))
      throw new PhaseTwoService.Problem(400,"LINE_INVALID","运营商和带宽最多100字，不能包含控制字符。");
    return value;
  }
  private static void saveLines(Connection c,long id,JsonObject body)throws Exception {
    for(String key:LINES) if(body.has(key)) {
      String value=lineValue(key,body.get(key).isJsonNull()?null:body.get(key).getAsString());
      try(PreparedStatement p=c.prepareStatement("UPDATE outlet_directory SET "+key+"=? WHERE id=?")) {
        p.setString(1,value);p.setLong(2,id);p.executeUpdate();
      }
    }
  }
  Map<String,Object> preview(String filename,byte[] raw,long actor)throws Exception {
    if(filename==null||!filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")||raw.length==0||raw.length>MAX_BYTES)
      throw new PhaseTwoService.Problem(400,"FILE_INVALID","请使用不超过5 MiB的.xlsx模板。");
    List<Map<String,String>> records=parse(raw);
    synchronized(PhaseTwoService.WRITE_LOCK) {try(Connection c=open()) {
      c.setAutoCommit(false);
      Map<String,Object> preview=buildPreview(c,records);
      String id=UUID.randomUUID().toString();
      try(PreparedStatement p=c.prepareStatement("INSERT INTO directory_import_job(id,actor_id,created_at,records_json,preview_json) VALUES(?,?,?,?,?)")) {
        p.setString(1,id);p.setLong(2,actor);p.setLong(3,System.currentTimeMillis());
        p.setString(4,gson.toJson(records));p.setString(5,gson.toJson(preview));p.executeUpdate();
      }
      audit(c,actor,"PREVIEW_DIRECTORY_IMPORT",id,"SUCCESS",String.valueOf(records.size()));c.commit();
      preview.put("id",id);return preview;
    }}
  }
  private Map<String,Object> buildPreview(Connection c,List<Map<String,String>> records)throws Exception {
    List<Map<String,Object>> items=new ArrayList<>();Set<String> seen=new HashSet<>();
    int inserted=0,updated=0,errors=0;
    for(Map<String,String> record:records) {
      Map<String,Object> item=new LinkedHashMap<>();item.put("rowNumber",record.get("_row"));
      item.put("outletNo",record.get("outlet_no"));item.put("branchCode",record.get("branch_code"));
      try {
        long branch=branchId(c,record.get("branch_code"));
        if(blank(record.get("outlet_no")).isEmpty()||record.get("outlet_no").length()>64)
          throw new IllegalArgumentException("网点号必填，最多64字。");
        if(!seen.add(branch+"|"+record.get("outlet_no")))throw new IllegalArgumentException("文件内同一网点重复，请合并到一行。");
        Map<String,Object> old=new LinkedHashMap<>();
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM outlet_directory WHERE branch_id=? AND outlet_no=?")) {
          p.setLong(1,branch);p.setString(2,record.get("outlet_no"));
          try(ResultSet r=p.executeQuery()) {if(r.next()) {
            for(int i=1;i<=r.getMetaData().getColumnCount();i++)old.put(r.getMetaData().getColumnLabel(i),r.getObject(i));
          }}
        }
        Map<String,Object> next=new LinkedHashMap<>(old);
        next.put("branch_id",branch);next.put("outlet_no",record.get("outlet_no"));
        String[] fields={"org_code","outlet_name","outlet_address","phone"};
        int[] limits={64,100,200,32};
        for(int i=0;i<fields.length;i++) {
          String value=blank(record.get(fields[i]));
          if(value.length()>limits[i])throw new IllegalArgumentException(fields[i]+"超过长度限制。");
          if(!value.isEmpty())next.put(fields[i],value);
        }
        if(blank((String)next.get("org_code")).isEmpty()||blank((String)next.get("outlet_name")).isEmpty())
          throw new IllegalArgumentException("新网点须填写机构编码和网点名称；仅补充模板须匹配已有网点。");
        for(String key:LINES)if(!blank(record.get(key)).isEmpty())next.put(key,lineValue(key,record.get(key)));
        item.put("before",old);item.put("after",next);
        item.put("action",old.isEmpty()?"新增":"更新");
        if(old.isEmpty())inserted++;else updated++;
      } catch(IllegalArgumentException|PhaseTwoService.Problem ex) {
        item.put("action","错误");item.put("message",ex.getMessage());errors++;
      }
      items.add(item);
    }
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("items",items);result.put("insertedRows",inserted);result.put("updatedRows",updated);
    result.put("errorRows",errors);result.put("totalRows",records.size());return result;
  }
  Map<String,Object> confirm(String id,long actor)throws Exception {
    synchronized(PhaseTwoService.WRITE_LOCK) {try(Connection c=open()) {
      c.setAutoCommit(false);
      String raw,snapshot,status;long created;
      try(PreparedStatement p=c.prepareStatement("SELECT * FROM directory_import_job WHERE id=? AND actor_id=?")) {
        p.setString(1,id);p.setLong(2,actor);
        try(ResultSet r=p.executeQuery()) {
          if(!r.next())throw new PhaseTwoService.Problem(404,"PREVIEW_MISSING","预览不存在，请重新上传。");
          raw=r.getString("records_json");snapshot=r.getString("preview_json");status=r.getString("status");created=r.getLong("created_at");
        }
      }
      if(status.equals("DONE"))return gson.fromJson(snapshot,Map.class);
      if(!status.equals("READY")||System.currentTimeMillis()-created>30*60*1000)
        throw new PhaseTwoService.Problem(409,"PREVIEW_EXPIRED","预览已失效，请重新上传。");
      java.lang.reflect.Type type=new com.google.gson.reflect.TypeToken<List<Map<String,String>>>(){}.getType();
      Map<String,Object> preview=buildPreview(c,gson.fromJson(raw,type));
      if(!gson.toJson(preview).equals(snapshot))
        throw new PhaseTwoService.Problem(409,"PREVIEW_CHANGED","预览后网点资料已变化，请重新上传核对。");
      if(((Number)preview.get("errorRows")).intValue()>0)
        throw new PhaseTwoService.Problem(400,"PREVIEW_ERRORS","请修正错误行后重新上传。");
      for(Map<String,Object> item:(List<Map<String,Object>>)preview.get("items")) {
        Map<String,Object> next=(Map<String,Object>)item.get("after");
        long rowId;
        if(item.get("action").equals("新增")) {
          try(PreparedStatement p=c.prepareStatement("INSERT INTO outlet_directory(branch_id,outlet_no,org_code,outlet_name,outlet_address,phone) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            p.setObject(1,next.get("branch_id"));p.setObject(2,next.get("outlet_no"));p.setObject(3,next.get("org_code"));p.setObject(4,next.get("outlet_name"));p.setObject(5,next.get("outlet_address"));p.setObject(6,next.get("phone"));p.executeUpdate();
            try(ResultSet r=p.getGeneratedKeys()){r.next();rowId=r.getLong(1);}
          }
        } else {
          rowId=((Number)next.get("id")).longValue();
          try(PreparedStatement p=c.prepareStatement("UPDATE outlet_directory SET org_code=?,outlet_name=?,outlet_address=?,phone=?,updated_at=? WHERE id=?")) {
            p.setObject(1,next.get("org_code"));p.setObject(2,next.get("outlet_name"));p.setObject(3,next.get("outlet_address"));p.setObject(4,next.get("phone"));p.setString(5,Instant.now().toString());p.setLong(6,rowId);p.executeUpdate();
          }
        }
        saveLines(c,rowId,gson.toJsonTree(next).getAsJsonObject());
      }
      try(PreparedStatement p=c.prepareStatement("UPDATE directory_import_job SET status='DONE' WHERE id=?")){p.setString(1,id);p.executeUpdate();}
      audit(c,actor,"CONFIRM_DIRECTORY_IMPORT",id,"SUCCESS",null);c.commit();return preview;
    }}
  }
  private static Map<String,Object> error(int row,String message){Map<String,Object> x=new LinkedHashMap<>();x.put("rowNumber",row);x.put("message",message);return x;}
  private static Map<String,Object> result(int total,int inserted,List<Map<String,Object>> errors){Map<String,Object> x=new LinkedHashMap<>();x.put("totalRows",total);x.put("insertedRows",inserted);x.put("errorRows",errors.size());x.put("errors",errors);return x;}
  private long branchId(Connection c,String code)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT id FROM branch WHERE code=? AND is_active=1 AND deleted_at IS NULL")){p.setString(1,code);ResultSet r=p.executeQuery();if(!r.next())throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_BRANCH_INVALID","所属支行不存在、未启用，或已删除："+code+"。");return r.getLong(1);}}
  private static void ensureActiveBranch(Connection c,long id)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM branch WHERE id=? AND is_active=1 AND deleted_at IS NULL")){p.setLong(1,id);if(!p.executeQuery().next())throw new PhaseTwoService.Problem(400,"OUTLET_DIRECTORY_BRANCH_INVALID","请选择已启用的所属支行。");}}
  private static boolean exists(Connection c,String sql,String value)throws Exception{try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,value);return p.executeQuery().next();}}
  private static boolean existsOtherOutlet(Connection c,String value,long branch,long id)throws Exception{try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM outlet_directory WHERE outlet_no=? AND branch_id=? AND id<>?")){p.setString(1,value);p.setLong(2,branch);p.setLong(3,id);return p.executeQuery().next();}}
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
