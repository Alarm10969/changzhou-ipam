package com.icbc.changzhou.ipam;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Administrator import of allocations already in use. Validation never allocates an IP. */
final class AssignmentImportService {
  static final String[] HEADERS={"IP地址","资产类型","资产名称","支行名称（核对用）","网点名称（核对用）","资源编码","使用人姓名","统一认证号","负责科技管理员账号","交换机IP","交换机端口","MAC地址","设备序列号","补录说明"};
  private static final int MAX_BYTES=5*1024*1024,MAX_ROWS=2000;
  private final Path dbFile;
  private final ManualAssignmentService manual;
  private final Gson json=new Gson();

  AssignmentImportService(Path file){dbFile=file;manual=new ManualAssignmentService(file);}

  Map<String,Object> preview(String fileName,byte[] raw,long actor)throws Exception{
    if(raw==null||raw.length==0||raw.length>MAX_BYTES)throw problem(400,"IMPORT_FILE_SIZE","文件必须大于 0 且不超过 5 MiB。");
    String format=format(fileName,raw);
    List<ImportRow> source=parse(format,raw);
    synchronized(PhaseTwoService.WRITE_LOCK){
      try(Connection c=open()){
        c.setAutoCommit(false);
        try{
          List<ImportRow> rows=validate(c,source);
          long id=insert(c,"INSERT INTO assignment_import_job(original_file_name,file_format,file_sha256,status,created_by) VALUES(?,?,?,'PREVIEW_READY',?)",safeName(fileName),format,sha(raw),actor);
          saveRows(c,id,rows);
          audit(c,actor,"ASSIGNMENT_IMPORT_PREVIEW",id,map("totalRows",rows.size()));
          Map<String,Object> view=view(c,job(c,id,actor),rows);
          c.commit();
          return view;
        }catch(Exception e){c.rollback();throw e;}
      }
    }
  }

  Map<String,Object> get(long id,long actor)throws Exception{
    try(Connection c=open()){
      c.setAutoCommit(false);
      try{Map<String,Object> result=view(c,job(c,id,actor),loadRows(c,id));c.commit();return result;}catch(Exception e){c.rollback();throw e;}
    }
  }

  Map<String,Object> recent(long actor)throws Exception{
    try(Connection c=open()){
      String counts="COUNT(r.id) AS totalRows,SUM(CASE WHEN r.result='READY' THEN 1 ELSE 0 END) AS readyRows,SUM(CASE WHEN r.result='DUPLICATE' THEN 1 ELSE 0 END) AS duplicateRows,SUM(CASE WHEN r.result='ERROR' THEN 1 ELSE 0 END) AS errorRows,SUM(CASE WHEN r.result='IMPORTED' THEN 1 ELSE 0 END) AS importedRows,CASE WHEN j.status='PREVIEW_READY' THEN SUM(CASE WHEN r.result IN ('DUPLICATE','ERROR') THEN 1 ELSE 0 END) ELSE COUNT(r.id)-SUM(CASE WHEN r.result='IMPORTED' THEN 1 ELSE 0 END) END AS notImportedRows";
      return map("items",list(c,"SELECT j.id,j.original_file_name AS fileName,j.status,j.preview_version AS previewVersion,j.created_at AS createdAt,j.completed_at AS completedAt,"+counts+" FROM assignment_import_job j LEFT JOIN assignment_import_row r ON r.job_id=j.id WHERE j.created_by=? GROUP BY j.id ORDER BY j.id DESC LIMIT 20",actor));
    }
  }

  Map<String,Object> confirm(long id,long actor,int previewVersion,boolean acceptPartial)throws Exception{
    synchronized(PhaseTwoService.WRITE_LOCK){
      try(Connection c=open()){
        c.setAutoCommit(false);
        try{
          Map<String,Object> job=job(c,id,actor);
          List<ImportRow> before=loadRows(c,id);
          if("COMPLETED".equals(job.get("status"))){Map<String,Object> result=view(c,job,before);c.commit();return result;}
          if(!"PREVIEW_READY".equals(job.get("status")))throw problem(409,"IMPORT_NOT_CONFIRMABLE","该批次已取消或失效，不能确认导入。");
          if(!acceptPartial)throw problem(400,"PARTIAL_IMPORT_CONFIRMATION_REQUIRED","请明确确认仅导入校验通过的行，其余行不导入。");
          if(n(job.get("previewVersion"))!=previewVersion){Map<String,Object> result=view(c,job,before);result.put("requiresReview",true);c.commit();return result;}
          List<ImportRow> current=validate(c,before);
          boolean changed=false;
          for(int i=0;i<before.size();i++)if(!before.get(i).snapshot.equals(current.get(i).snapshot)){changed=true;break;}
          if(changed){
            saveRows(c,id,current);
            change(c,"UPDATE assignment_import_job SET preview_version=preview_version+1,updated_at=? WHERE id=? AND status='PREVIEW_READY' AND preview_version=?",Instant.now().toString(),id,previewVersion);
            audit(c,actor,"ASSIGNMENT_IMPORT_REVIEW_REQUIRED",id,map("previousVersion",previewVersion));
            Map<String,Object> result=view(c,job(c,id,actor),current);result.put("requiresReview",true);
            c.commit();return result;
          }
          boolean hasReady=false;for(ImportRow row:current)if("READY".equals(row.result)){hasReady=true;break;}
          if(!hasReady)throw problem(409,"IMPORT_NO_READY_ROWS","该批次没有校验通过且待导入的行，请修正错误后重新上传。");
          int imported=0;
          for(ImportRow row:current)if("READY".equals(row.result)){
            Map<String,Object> allocation=manual.write(c,row.prepared,actor);
            row.assignmentId=n(allocation.get("id"));row.result="IMPORTED";row.code="IMPORTED";row.message="已补录为已分配。";imported++;
          }
          saveRows(c,id,current);
          String now=Instant.now().toString();
          change(c,"UPDATE assignment_import_job SET status='COMPLETED',confirmed_at=?,completed_at=?,updated_at=? WHERE id=? AND status='PREVIEW_READY' AND preview_version=?",now,now,now,id,previewVersion);
          audit(c,actor,"ASSIGNMENT_IMPORT_CONFIRM",id,map("importedRows",imported,"notImportedRows",current.size()-imported,"previewVersion",previewVersion));
          Map<String,Object> result=view(c,job(c,id,actor),current);result.put("requiresReview",false);
          c.commit();return result;
        }catch(Exception e){c.rollback();throw e;}
      }
    }
  }

  void cancel(long id,long actor)throws Exception{
    synchronized(PhaseTwoService.WRITE_LOCK){try(Connection c=open()){
      c.setAutoCommit(false);
      try{
        Map<String,Object> job=job(c,id,actor);
        if("CANCELLED".equals(job.get("status"))){c.commit();return;}
        if(!"PREVIEW_READY".equals(job.get("status")))throw problem(409,"IMPORT_NOT_CANCELLABLE","该批次已经完成或失效，不能取消。");
        change(c,"UPDATE assignment_import_job SET status='CANCELLED',updated_at=? WHERE id=? AND status='PREVIEW_READY'",Instant.now().toString(),id);
        audit(c,actor,"ASSIGNMENT_IMPORT_CANCEL",id,null);c.commit();
      }catch(Exception e){c.rollback();throw e;}
    }}
  }

  byte[] report(long id,long actor)throws Exception{
    try(Connection c=open()){
      c.setAutoCommit(false);
      Map<String,Object> currentJob=job(c,id,actor);
      boolean preview="PREVIEW_READY".equals(currentJob.get("status"));
      StringBuilder out=new StringBuilder("\uFEFF原始行号");for(String h:HEADERS)out.append(',').append(csv(h));out.append(",校验结果,原因代码,未导入原因\r\n");
      for(ImportRow row:loadRows(c,id))if(!"IMPORTED".equals(row.result)&&(!preview||!"READY".equals(row.result))){
        out.append(row.number);for(String h:HEADERS)out.append(',').append(csv(row.raw.get(h)));
        String result="ERROR".equals(row.result)?"错误":"DUPLICATE".equals(row.result)?"重复跳过":"待导入";
        out.append(',').append(csv(result)).append(',').append(csv(row.code)).append(',').append(csv(row.message)).append("\r\n");
      }
      byte[] result=out.toString().getBytes(StandardCharsets.UTF_8);c.commit();return result;
    }
  }

  byte[] template(String format)throws Exception{
    if("CSV".equalsIgnoreCase(format)){
      StringBuilder out=new StringBuilder("\uFEFF");for(int i=0;i<HEADERS.length;i++){if(i>0)out.append(',');out.append(csv(HEADERS[i]));}out.append("\r\n");return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    if(!"XLSX".equalsIgnoreCase(format))throw problem(400,"IMPORT_FORMAT_REJECTED","模板仅支持 CSV 或 XLSX。");
    try(Workbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      Sheet data=wb.createSheet("导入数据");Row header=data.createRow(0);
      CellStyle text=wb.createCellStyle();text.setDataFormat(wb.createDataFormat().getFormat("@"));
      CellStyle title=wb.createCellStyle();Font bold=wb.createFont();bold.setBold(true);title.setFont(bold);title.setWrapText(true);
      for(int i=0;i<HEADERS.length;i++){Cell cell=header.createCell(i);cell.setCellValue(HEADERS[i]);cell.setCellStyle(title);data.setColumnWidth(i,(i==13?42:24)*256);data.setDefaultColumnStyle(i,text);}
      data.createFreezePane(0,1);data.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,0,0,HEADERS.length-1));
      Sheet help=wb.createSheet("填写说明");
      String[] notes={"已分配 IP 批量补录：请只在“导入数据”工作表填写真实资料，示例不会被导入。","IP地址、资产类型、资产名称必填；资产类型填写“科技资产”或“非科技资产”。","IP 必须已存在于系统地址池；网关、已有不同分配、编码冲突行不导入。","支行名称（核对用）、网点名称（核对用）可留空；填写时必须与该 IP 的系统归属完全一致。","资源编码、使用人姓名、统一认证号按系统资产关联规则填写；不需要的字段请留空。","负责科技管理员账号选填；填写账号而不是姓名。交换机IP、交换机端口、MAC地址、设备序列号、补录说明选填。","编号和认证号为文本，请保留前导零。请勿填写公式、外部链接或宏。","先校验预览，再确认仅导入通过行。错误和重复行可下载未导入明细；已有资料不会被覆盖。","相同 IP 的完全相同行仅第一行参与；同 IP 信息不同或同资源编码用于不同 IP 时，相关行均报错。","每批最多 2000 条，文件不超过 5 MiB。请保留 14 个表头；不填写真实资料时数据页应为空。","示例（仅说明）：IP地址=系统内实际IP；资产类型=非科技资产；资产名称=系统已有资产名称。"};
      for(int i=0;i<notes.length;i++)help.createRow(i).createCell(0).setCellValue(notes[i]);help.setColumnWidth(0,100*256);
      wb.write(out);return out.toByteArray();
    }
  }

  private List<ImportRow> validate(Connection c,List<ImportRow> source)throws Exception{
    List<ImportRow> rows=new ArrayList<>();
    for(ImportRow original:source){
      ImportRow row=new ImportRow(original.number,original.raw);rows.add(row);
      Map<String,Object> context=new LinkedHashMap<>();row.context=context;
      String ip=trim(row.raw.get(HEADERS[0])),type=assetType(row.raw.get(HEADERS[1])),assetName=trim(row.raw.get(HEADERS[2]));
      row.ipAddress=ip;row.assetType=type;row.assetName=assetName;
      // Snapshot even invalid rows so that rule/ownership changes cannot silently alter a reviewed batch.
      Map<String,Object> pool=one(c,"SELECT p.id,p.ip_address AS ipAddress,p.is_active,p.is_reserved,p.is_occupied,p.reservation_reason,p.updated_at,p.deleted_at,r.id AS rangeId,r.branch_id,r.outlet_id,r.cidr,r.gateway,r.range_type,r.is_active AS rangeActive,r.updated_at AS rangeUpdatedAt,r.deleted_at AS rangeDeletedAt,b.name AS branchName,b.is_active AS branchActive,b.updated_at AS branchUpdatedAt,b.deleted_at AS branchDeletedAt,o.name AS outletName,o.is_active AS outletActive,o.updated_at AS outletUpdatedAt,o.deleted_at AS outletDeletedAt,d.id AS assignmentId,d.version AS assignmentVersion,d.assignment_source,d.manual_asset_id,d.manual_asset_type,d.manual_asset_name,d.manual_requires_user,d.manual_requires_resource_code,d.resource_code,d.manual_asset_code,d.manual_user_name,d.manual_user_unified_auth_id,d.responsible_tech_user_id,d.switch_ip,d.switch_port,d.mac_address,d.serial_number,d.manual_note FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id JOIN branch b ON b.id=r.branch_id LEFT JOIN outlet o ON o.id=r.outlet_id LEFT JOIN ip_assignment d ON d.ip_pool_id=p.id AND d.status='ASSIGNED' WHERE p.ip_address=? AND p.deleted_at IS NULL",ip);
      context.put("pool",pool);
      Map<String,Object> asset=one(c,"SELECT id,asset_type,name,requires_user,requires_resource_code,recommended_range_type,is_active,deleted_at,updated_at FROM asset_definition WHERE asset_type=? AND name=? AND deleted_at IS NULL",type,assetName);context.put("asset",asset);
      String techName=trim(row.raw.get(HEADERS[8]));
      Map<String,Object> tech=techName.isEmpty()?null:one(c,"SELECT id,username,real_name,role,branch_id,outlet_id,is_active,deleted_at,version,updated_at FROM sys_user WHERE username=? AND deleted_at IS NULL",techName);context.put("responsible",tech);
      String code=trim(row.raw.get(HEADERS[5]));
      context.put("resourceCodeAssignment",code.isEmpty()?null:one(c,"SELECT id,ip_pool_id,status,version,resource_code FROM ip_assignment WHERE resource_code=? AND status='ASSIGNED'",code));
      if(pool!=null){row.branchName=string(pool.get("branchName"));row.outletName=string(pool.get("outletName"));}
      try{
        row.ipAddress=validIp(ip);
        if(!"TECH".equals(type)&&!"NON_TECH".equals(type))throw problem(400,"ASSET_TYPE_INVALID","资产类型须为科技资产或非科技资产。");
        if(assetName.isEmpty()||assetName.length()>100)throw problem(400,"ASSET_INVALID","请填写系统中已有的资产名称，最长 100 个字符。");
        if(pool==null||pool.get("rangeDeletedAt")!=null||pool.get("branchDeletedAt")!=null||pool.get("outletDeletedAt")!=null)throw problem(400,"IP_NOT_FOUND","IP 未在现有资源池中或所属网段、支行、网点已删除。");
        if(asset==null||n(asset.get("is_active"))!=1)throw problem(400,"ASSET_INVALID","该类型下未找到已启用的资产名称。");
        String branchCheck=trim(row.raw.get(HEADERS[3])),outletCheck=trim(row.raw.get(HEADERS[4]));
        if(!branchCheck.isEmpty()&&!branchCheck.equals(row.branchName))throw problem(400,"BRANCH_MISMATCH","支行名称与该 IP 的系统归属不一致。");
        if(!outletCheck.isEmpty()&&!outletCheck.equals(row.outletName))throw problem(400,"OUTLET_MISMATCH","网点名称与该 IP 的系统归属不一致。");
        if(!techName.isEmpty()&&(tech==null||!"TECH_ADMIN".equals(tech.get("role"))||n(tech.get("is_active"))!=1))throw problem(400,"RESPONSIBLE_TECH_INVALID","负责科技管理员账号不存在或未启用。");
        JsonObject body=new JsonObject();body.addProperty("assetId",n(asset.get("id")));body.addProperty("assetType",type);
        String[] keys={"assetCode","userName","userUnifiedAuthId","switchIp","switchPort","macAddress","serialNumber","note"};int[] indices={5,6,7,9,10,11,12,13};
        for(int i=0;i<keys.length;i++)body.addProperty(keys[i],row.raw.get(HEADERS[indices[i]]));
        if(tech!=null)body.addProperty("responsibleTechUserId",n(tech.get("id")));
        row.prepared=manual.prepare(c,n(pool.get("id")),body,true);
        row.result=row.prepared.duplicate?"DUPLICATE":"READY";row.code=row.prepared.duplicate?"ALREADY_REGISTERED":"READY";
        row.message=row.prepared.duplicate?"已有完全相同的手工补录记录，本行跳过。":"校验通过，将补录为已分配。";
        if(row.prepared.duplicate)row.assignmentId=n(row.prepared.pool.get("assignmentId"));
        context.put("prepared",row.prepared.snapshot);
      }catch(PhaseTwoService.Problem e){row.error(e.code,e.getMessage());}
    }
    Map<String,List<ImportRow>> byIp=new LinkedHashMap<>(),byCode=new LinkedHashMap<>();
    for(ImportRow row:rows){
      if(!row.ipAddress.isEmpty())byIp.computeIfAbsent(row.ipAddress,k->new ArrayList<>()).add(row);
      String code=trim(row.raw.get(HEADERS[5]));if(!code.isEmpty())byCode.computeIfAbsent(code,k->new ArrayList<>()).add(row);
    }
    for(List<ImportRow> group:byIp.values())if(group.size()>1){
      ImportRow first=group.get(0);String identity=identity(first);boolean same=true;
      for(ImportRow row:group)if(!identity.equals(identity(row)))same=false;
      if(!same){for(ImportRow row:group)row.error("BATCH_IP_CONFLICT","文件中同一 IP 对应不同信息，相关行均未导入。");}
      else if(!"ERROR".equals(first.result))for(int i=1;i<group.size();i++){ImportRow row=group.get(i);row.result="DUPLICATE";row.code="DUPLICATE_FILE_ROW";row.message="与第 "+first.number+" 行完全相同，本行跳过。";}
    }
    for(List<ImportRow> group:byCode.values())if(group.size()>1){
      Set<String> addresses=new HashSet<>();for(ImportRow row:group)addresses.add(row.ipAddress);
      if(addresses.size()>1)for(ImportRow row:group)row.error("BATCH_RESOURCE_CODE_CONFLICT","文件中同一资源编码对应多个 IP，相关行均未导入。");
    }
    for(ImportRow row:rows)row.snapshot=json.toJson(map("context",row.context,"result",row.result,"code",row.code,"message",row.message));
    return rows;
  }

  private String identity(ImportRow row){
    if(row.prepared!=null)return json.toJson(map("ip",row.ipAddress,"normalized",row.prepared.normalized,"branch",row.branchName,"outlet",row.outletName));
    Map<String,String> values=new LinkedHashMap<>();for(String h:HEADERS)values.put(h,trim(row.raw.get(h)));values.put(HEADERS[1],row.assetType);return json.toJson(values);
  }

  private Map<String,Object> view(Connection c,Map<String,Object> job,List<ImportRow> rows)throws Exception{
    int ready=0,duplicate=0,error=0,imported=0;List<Map<String,Object>> items=new ArrayList<>();
    for(ImportRow row:rows){
      if("READY".equals(row.result))ready++;else if("DUPLICATE".equals(row.result))duplicate++;else if("ERROR".equals(row.result))error++;else if("IMPORTED".equals(row.result))imported++;
      Map<String,Object> item=map("rowNumber",row.number,"ipAddress",row.ipAddress,"assetType",row.assetType,"assetName",row.assetName,"branchName",row.branchName,"outletName",row.outletName,"result",row.result,"code",row.code,"message",row.message,"fields",row.raw);
      JsonObject snapshot=json.fromJson(row.snapshot,JsonObject.class);
      JsonObject context=snapshot.getAsJsonObject("context");
      if(context!=null&&context.has("prepared")&&context.get("prepared").isJsonObject())item.put("normalizedFields",context.getAsJsonObject("prepared").getAsJsonObject("normalized"));
      if(row.assignmentId!=0)item.put("assignmentId",row.assignmentId);items.add(item);
    }
    int notImported="PREVIEW_READY".equals(job.get("status"))?duplicate+error:rows.size()-imported;
    Map<String,Object> result=new LinkedHashMap<>(job);result.putAll(map("totalRows",rows.size(),"readyRows",ready,"duplicateRows",duplicate,"errorRows",error,"importedRows",imported,"notImportedRows",notImported,"rows",items));return result;
  }

  private List<ImportRow> loadRows(Connection c,long id)throws Exception{
    List<ImportRow> rows=new ArrayList<>();
    for(Map<String,Object> stored:list(c,"SELECT row_number,raw_json,snapshot_json,result,error_code,error_message,assignment_id FROM assignment_import_row WHERE job_id=? ORDER BY row_number",id)){
      JsonObject raw=json.fromJson(string(stored.get("raw_json")),JsonObject.class);Map<String,String> values=new LinkedHashMap<>();for(String h:HEADERS)values.put(h,raw.has(h)&&!raw.get(h).isJsonNull()?raw.get(h).getAsString():"");
      ImportRow row=new ImportRow((int)n(stored.get("row_number")),values);row.snapshot=string(stored.get("snapshot_json"));row.result=string(stored.get("result"));row.code=string(stored.get("error_code"));row.message=string(stored.get("error_message"));row.assignmentId=n(stored.get("assignment_id"));
      row.ipAddress=trim(values.get(HEADERS[0]));row.assetType=assetType(values.get(HEADERS[1]));row.assetName=trim(values.get(HEADERS[2]));
      JsonObject snapshot=json.fromJson(row.snapshot,JsonObject.class);JsonObject context=snapshot.getAsJsonObject("context");
      if(context!=null&&context.has("pool")&&context.get("pool").isJsonObject()){JsonObject pool=context.getAsJsonObject("pool");row.branchName=jsonString(pool,"branchName");row.outletName=jsonString(pool,"outletName");}
      rows.add(row);
    }
    return rows;
  }

  private void saveRows(Connection c,long id,List<ImportRow> rows)throws Exception{
    try(PreparedStatement p=c.prepareStatement("DELETE FROM assignment_import_row WHERE job_id=?")){p.setLong(1,id);p.executeUpdate();}
    try(PreparedStatement p=c.prepareStatement("INSERT INTO assignment_import_row(job_id,row_number,raw_json,snapshot_json,result,error_code,error_message,assignment_id) VALUES(?,?,?,?,?,?,?,?)")){
      for(ImportRow row:rows){p.setLong(1,id);p.setInt(2,row.number);p.setString(3,json.toJson(row.raw));p.setString(4,row.snapshot);p.setString(5,row.result);p.setString(6,row.code);p.setString(7,row.message);if(row.assignmentId==0)p.setNull(8,Types.INTEGER);else p.setLong(8,row.assignmentId);p.addBatch();}p.executeBatch();
    }
  }

  private Map<String,Object> job(Connection c,long id,long actor)throws Exception{
    Map<String,Object> result=one(c,"SELECT id,original_file_name AS fileName,status,preview_version AS previewVersion,created_at AS createdAt,completed_at AS completedAt FROM assignment_import_job WHERE id=? AND created_by=?",id,actor);
    if(result==null)throw problem(404,"IMPORT_NOT_FOUND","导入批次不存在或不属于当前管理员。");return result;
  }

  private List<ImportRow> parse(String format,byte[] raw)throws Exception{return "CSV".equals(format)?parseCsv(raw):parseXlsx(raw);}

  private List<ImportRow> parseCsv(byte[] raw)throws Exception{
    String input;
    try{input=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}catch(CharacterCodingException e){throw problem(400,"CSV_ENCODING","CSV 必须使用 UTF-8 编码。");}
    if(input.startsWith("\uFEFF"))input=input.substring(1);
    List<List<String>> table=new ArrayList<>();List<Integer> numbers=new ArrayList<>();List<String> line=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,closed=false;int physical=1,start=1;
    for(int i=0;i<input.length();i++){
      char ch=input.charAt(i);
      if(quoted){
        if(ch=='"'){if(i+1<input.length()&&input.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else{cell.append(ch);if(ch=='\n')physical++;}
      }else if(ch=='"'){
        if(cell.length()!=0||closed)throw problem(400,"CSV_QUOTE","CSV 引号格式不正确。");quoted=true;
      }else if(ch==','){line.add(cell.toString());cell.setLength(0);closed=false;
      }else if(ch=='\n'||ch=='\r'){
        if(ch=='\r'&&i+1<input.length()&&input.charAt(i+1)=='\n')i++;
        line.add(cell.toString());if(!blank(line)){table.add(line);numbers.add(start);}line=new ArrayList<>();cell.setLength(0);closed=false;physical++;start=physical;
      }else{if(closed)throw problem(400,"CSV_QUOTE","CSV 引号结束后只能是分隔符或换行。");cell.append(ch);}
      if(table.size()>MAX_ROWS+1)throw problem(400,"IMPORT_TOO_MANY_ROWS","数据行不能超过 2000 条。");
    }
    if(quoted)throw problem(400,"CSV_QUOTE","CSV 引号未闭合。");
    if(cell.length()>0||!line.isEmpty()||closed){line.add(cell.toString());if(!blank(line)){table.add(line);numbers.add(start);}}
    return table(table,numbers);
  }

  private List<ImportRow> parseXlsx(byte[] raw)throws Exception{
    Path file=Files.createTempFile("ipam-assignment-import-",".xlsx");
    try{
      Files.write(file,raw);
      try(ZipFile zip=new ZipFile(file.toFile())){
        long total=0;Enumeration<? extends ZipEntry> entries=zip.entries();
        while(entries.hasMoreElements()){
          ZipEntry entry=entries.nextElement();String name=entry.getName();long size=entry.getSize();if(size>0)total+=size;
          if(size>10L*1024*1024||total>40L*1024*1024||name.toLowerCase(Locale.ROOT).contains("vbaproject")||name.startsWith("xl/externalLinks/")||name.equals("EncryptionInfo"))throw problem(400,"XLSX_UNSAFE","Excel 含宏、外部链接、加密或过大的内容。");
          if(name.endsWith(".rels")){try(InputStream in=zip.getInputStream(entry)){String rels=readLimited(in,2*1024*1024);if(rels.matches("(?is).*TargetMode\\s*=\\s*['\"]External['\"].*"))throw problem(400,"XLSX_UNSAFE","Excel 含外部链接。");}}
        }
      }
      ZipSecureFile.setMinInflateRatio(0.01);ZipSecureFile.setMaxEntrySize(10L*1024*1024);ZipSecureFile.setMaxTextSize(10L*1024*1024);
      try(Workbook workbook=new XSSFWorkbook(file.toFile())){
        for(Sheet sheet:workbook)for(Row row:sheet)for(Cell cell:row)if(cell.getCellType()==CellType.FORMULA)throw problem(400,"XLSX_FORMULA","任何工作表均不允许公式单元格。");
        Sheet data=workbook.getSheet("导入数据");if(data==null){if(workbook.getNumberOfSheets()!=1)throw problem(400,"XLSX_SHEETS","多工作表文件必须包含名为“导入数据”的工作表。");data=workbook.getSheetAt(0);}
        DataFormatter formatter=new DataFormatter(Locale.ROOT);List<List<String>> table=new ArrayList<>();List<Integer> numbers=new ArrayList<>();
        for(Row row:data){
          List<String> values=new ArrayList<>();boolean nonempty=false;
          for(int i=0;i<row.getLastCellNum();i++){Cell cell=row.getCell(i,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);String value=cell==null?"":formatter.formatCellValue(cell);values.add(value);if(!trim(value).isEmpty())nonempty=true;}
          if(nonempty){table.add(values);numbers.add(row.getRowNum()+1);}
          if(table.size()>MAX_ROWS+1)throw problem(400,"IMPORT_TOO_MANY_ROWS","数据行不能超过 2000 条。");
        }
        return table(table,numbers);
      }
    }catch(PhaseTwoService.Problem e){throw e;}catch(Exception e){throw problem(400,"XLSX_INVALID","无法读取 XLSX，请使用无宏、无公式的有效 Excel 文件。");}finally{Files.deleteIfExists(file);}
  }

  private List<ImportRow> table(List<List<String>> input,List<Integer> numbers){
    while(!input.isEmpty()&&blank(input.get(0))){input.remove(0);numbers.remove(0);}
    if(input.isEmpty())throw problem(400,"IMPORT_EMPTY","文件中没有表头。");
    List<String> header=new ArrayList<>();for(String h:input.get(0))header.add(trim(h));
    while(!header.isEmpty()&&header.get(header.size()-1).isEmpty())header.remove(header.size()-1);
    if(header.size()!=HEADERS.length||new HashSet<>(header).size()!=HEADERS.length||!new HashSet<>(header).equals(new HashSet<>(Arrays.asList(HEADERS))))throw problem(400,"IMPORT_HEADER_INVALID","请使用统一模板的 14 个中文表头，不能缺失、重复或新增列。");
    List<ImportRow> rows=new ArrayList<>();
    for(int i=1;i<input.size();i++){
      List<String> values=input.get(i);if(blank(values))continue;
      if(values.size()>header.size())for(int j=header.size();j<values.size();j++)if(!trim(values.get(j)).isEmpty())throw problem(400,"IMPORT_COLUMN_INVALID","第 "+numbers.get(i)+" 行存在表头以外的数据列。");
      Map<String,String> value=new LinkedHashMap<>();for(String h:HEADERS){int index=header.indexOf(h);value.put(h,index<values.size()?values.get(index):"");}
      rows.add(new ImportRow(numbers.get(i),value));
      if(rows.size()>MAX_ROWS)throw problem(400,"IMPORT_TOO_MANY_ROWS","数据行不能超过 2000 条。");
    }
    if(rows.isEmpty())throw problem(400,"IMPORT_EMPTY","没有可导入的数据行，请先填写“导入数据”。");return rows;
  }

  private Connection open()throws SQLException{Connection c=DriverManager.getConnection("jdbc:sqlite:"+dbFile);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;}
  private static String format(String name,byte[] raw){String n=string(name).toLowerCase(Locale.ROOT);if(n.endsWith(".csv"))return "CSV";if(n.endsWith(".xlsx")&&raw.length>4&&raw[0]=='P'&&raw[1]=='K')return "XLSX";throw problem(400,"IMPORT_FORMAT_REJECTED","仅支持 UTF-8 CSV 或无宏 XLSX，不支持 XLS/XLSM。");}
  private static String assetType(String value){String type=trim(value);if("科技资产".equals(type)||"TECH".equalsIgnoreCase(type))return "TECH";if("非科技资产".equals(type)||"NON_TECH".equalsIgnoreCase(type))return "NON_TECH";return type;}
  private static String validIp(String value){if(!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))throw problem(400,"IP_INVALID","IP 必须为不含前导零的 IPv4 地址。");try{PhaseFourService.Net.ipv4(value);}catch(Exception e){throw problem(400,"IP_INVALID","IP 必须为合法 IPv4 地址。");}return value;}
  private static String safeName(String value){String name=string(value).replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);if(name.isEmpty())name="upload";return name.length()>255?name.substring(0,255):name;}
  private static String readLimited(InputStream in,int maximum)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int read;while((read=in.read(bytes))!=-1){if(out.size()+read>maximum)throw new IOException("XML relationship file exceeds limit");out.write(bytes,0,read);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
  private static String sha(byte[] raw)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(raw))out.append(String.format("%02x",b));return out.toString();}
  private static String csv(Object value){String text=string(value);int first=0;while(first<text.length()&&Character.isWhitespace(text.charAt(first)))first++;if(first<text.length()&&"=+-@".indexOf(text.charAt(first))>=0)text="'"+text;return '"'+text.replace("\"","\"\"")+'"';}
  private static boolean blank(List<String> row){for(String s:row)if(!trim(s).isEmpty())return false;return true;}
  private static String jsonString(JsonObject object,String key){JsonElement value=object.get(key);return value==null||value.isJsonNull()?"":value.getAsString();}
  private static String trim(String value){return value==null?"":value.trim();}
  private static String string(Object value){return value==null?"":String.valueOf(value);}
  private static long n(Object value){return value==null?0:((Number)value).longValue();}
  private static PhaseTwoService.Problem problem(int status,String code,String message){return new PhaseTwoService.Problem(status,code,message);}
  private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put(String.valueOf(values[i]),values[i+1]);return result;}
  private static void bind(PreparedStatement p,Object...values)throws SQLException{for(int i=0;i<values.length;i++){Object value=values[i];if(value==null)p.setNull(i+1,Types.VARCHAR);else if(value instanceof Number)p.setLong(i+1,((Number)value).longValue());else p.setString(i+1,String.valueOf(value));}}
  private static List<Map<String,Object>> list(Connection c,String sql,Object...values)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql)){bind(p,values);try(ResultSet r=p.executeQuery()){List<Map<String,Object>> result=new ArrayList<>();ResultSetMetaData md=r.getMetaData();while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=md.getColumnCount();i++)row.put(md.getColumnLabel(i),r.getObject(i));result.add(row);}return result;}}}
  private static Map<String,Object> one(Connection c,String sql,Object...values)throws SQLException{List<Map<String,Object>> rows=list(c,sql,values);return rows.isEmpty()?null:rows.get(0);}
  private static long insert(Connection c,String sql,Object...values)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(p,values);p.executeUpdate();try(ResultSet keys=p.getGeneratedKeys()){if(!keys.next())throw new SQLException("Missing generated key");return keys.getLong(1);}}}
  private static void change(Connection c,String sql,Object...values)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql)){bind(p,values);if(p.executeUpdate()!=1)throw problem(409,"IMPORT_CONFLICT","导入状态已变化，请重新查看批次。");}}
  private void audit(Connection c,long actor,String action,long id,Object detail)throws SQLException{try(PreparedStatement p=c.prepareStatement("INSERT INTO audit_log(actor_user_id,action,target_type,target_id,result,detail_json) VALUES(?,?,'ASSIGNMENT_IMPORT',?,'SUCCESS',?)")){p.setLong(1,actor);p.setString(2,action);p.setString(3,String.valueOf(id));p.setString(4,detail==null?null:json.toJson(detail));p.executeUpdate();}}

  private static final class ImportRow{
    final int number;final Map<String,String> raw;
    Map<String,Object> context;ManualAssignmentService.PreparedRegistration prepared;
    String snapshot="",result="ERROR",code="",message="",ipAddress="",assetType="",assetName="",branchName="",outletName="";
    long assignmentId;
    ImportRow(int row,Map<String,String> values){number=row;raw=new LinkedHashMap<>(values);}
    void error(String errorCode,String errorMessage){result="ERROR";code=errorCode;message=errorMessage;assignmentId=0;}
  }
}
