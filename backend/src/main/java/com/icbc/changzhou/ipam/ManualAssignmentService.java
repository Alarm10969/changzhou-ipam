package com.icbc.changzhou.ipam;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Administrator-only registration of existing allocations and gateway relocation. */
final class ManualAssignmentService {
  private final Path dbFile;
  private final Gson json=new Gson();
  ManualAssignmentService(Path file){dbFile=file;}

  Map<String,Object> options(long poolId)throws Exception{
    try(Connection c=open()){
      Map<String,Object> pool=one(c,"SELECT p.id,p.ip_address AS ipAddress,p.is_active AS active,p.is_reserved AS reserved,p.is_occupied AS occupied,p.reservation_reason AS reservationReason,r.id AS rangeId,r.cidr,r.gateway,r.range_type AS rangeType,b.id AS branchId,b.name AS branchName,o.id AS outletId,o.name AS outletName,d.id AS assignmentId FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id JOIN branch b ON b.id=r.branch_id LEFT JOIN outlet o ON o.id=r.outlet_id LEFT JOIN ip_assignment d ON d.ip_pool_id=p.id AND d.status='ASSIGNED' WHERE p.id=? AND p.deleted_at IS NULL AND r.deleted_at IS NULL AND b.deleted_at IS NULL AND (o.id IS NULL OR o.deleted_at IS NULL)",poolId);
      if(pool==null)throw problem(404,"IP_NOT_FOUND","IP 不存在或已删除。");
      pool.put("state",state(pool));
      pool.put("sourceState",sourceState(pool));
      long outlet=n(pool.get("outletId")),branch=n(pool.get("branchId"));
      List<Map<String,Object>> assets=list(c,"SELECT id,asset_type AS assetType,name,requires_user AS requiresUser,requires_resource_code AS requiresResourceCode,recommended_range_type AS recommendedRangeType FROM asset_definition WHERE is_active=1 AND deleted_at IS NULL ORDER BY asset_type,name");
      List<Map<String,Object>> tech=list(c,"SELECT id,username,real_name AS realName FROM sys_user WHERE role='TECH_ADMIN' AND is_active=1 AND deleted_at IS NULL AND (branch_id IS NULL OR branch_id=?) AND (outlet_id IS NULL OR outlet_id=?) ORDER BY username",branch,outlet);
      return map("pool",pool,"assets",assets,"responsibleTechAdmins",tech);
    }
  }

  Map<String,Object> register(long poolId,JsonObject b,long actor)throws Exception{
    synchronized(PhaseTwoService.WRITE_LOCK){
      try(Connection c=open()){
        c.setAutoCommit(false);
        try{
          PreparedRegistration prepared=prepare(c,poolId,b,false);
          Map<String,Object> result=write(c,prepared,actor);
          c.commit();
          return result;
        }catch(Exception e){c.rollback();throw e;}
      }
    }
  }

  /** Read-only validation shared by individual and batch registration. The caller owns the transaction. */
  PreparedRegistration prepare(Connection c,long poolId,JsonObject b,boolean allowIdentical)throws Exception{
    long assetId=number(b,"assetId");
    Map<String,Object> pool=one(c,"SELECT p.id,p.ip_address AS ipAddress,p.is_active AS active,p.is_reserved AS reserved,p.is_occupied AS occupied,p.reservation_reason AS reservationReason,p.updated_at AS poolUpdatedAt,r.id AS rangeId,r.cidr,r.gateway,r.range_type AS rangeType,r.updated_at AS rangeUpdatedAt,b.id AS branchId,b.name AS branchName,b.updated_at AS branchUpdatedAt,o.id AS outletId,o.name AS outletName,o.updated_at AS outletUpdatedAt,d.id AS assignmentId,d.version AS assignmentVersion FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id JOIN branch b ON b.id=r.branch_id LEFT JOIN outlet o ON o.id=r.outlet_id LEFT JOIN ip_assignment d ON d.ip_pool_id=p.id AND d.status='ASSIGNED' WHERE p.id=? AND p.deleted_at IS NULL AND r.deleted_at IS NULL AND b.deleted_at IS NULL AND (o.id IS NULL OR o.deleted_at IS NULL)",poolId);
    if(pool==null)throw problem(404,"IP_NOT_FOUND","IP 不存在或已删除。");
    if(pool.get("assignmentId")!=null&&!allowIdentical)throw problem(409,"IP_ALREADY_ASSIGNED","该 IP 已分配，不能重复补录。");
    if(value(pool.get("ipAddress")).equals(value(pool.get("gateway"))))throw problem(400,"SYSTEM_RESERVED_PROTECTED","当前网关是系统保留地址，请先通过调整网关移走后再补录。");
    String originalSource=sourceState(pool),source="OCCUPIED".equals(originalSource)?"AVAILABLE":originalSource;
    if(source==null&&pool.get("assignmentId")==null)throw problem(409,"IP_NOT_AVAILABLE","该 IP 当前状态不能补录。");
    Map<String,Object> asset=one(c,"SELECT id,asset_type AS assetType,name,requires_user AS requiresUser,requires_resource_code AS requiresResourceCode,recommended_range_type AS recommendedRangeType,updated_at AS assetUpdatedAt FROM asset_definition WHERE id=? AND is_active=1 AND deleted_at IS NULL",assetId);
    if(asset==null)throw problem(400,"ASSET_INVALID","请选择已启用的资产小类。");
    String requestedType=optional(b,"assetType",16);if(requestedType!=null&&!requestedType.equals(value(asset.get("assetType"))))throw problem(400,"ASSET_TYPE_MISMATCH","资产类型与资产小类不一致，请重新选择。");
    boolean needsUser=n(asset.get("requiresUser"))==1,needsCode=n(asset.get("requiresResourceCode"))==1;
    String userName=optional(b,"userName",100),userAuth=optional(b,"userUnifiedAuthId",64),code=optional(b,"assetCode",100);
    if(needsUser){if(userName==null||userAuth==null)throw problem(400,"ASSET_USER_REQUIRED","该资产要求使用人姓名和统一认证号。");}else if(userName!=null||userAuth!=null)throw problem(400,"ASSET_USER_FORBIDDEN","该资产不要求使用人信息，请勿填写。");
    if(needsCode){if(code==null)throw problem(400,"ASSET_CODE_REQUIRED","该资产要求资源编码。");}else if(code!=null)throw problem(400,"ASSET_CODE_FORBIDDEN","该资产不要求资源编码，请勿填写。");
    Long responsible=optionalLong(b,"responsibleTechUserId");
    Map<String,Object> tech=null;
    if(responsible!=null){
      tech=one(c,"SELECT id,username,real_name,branch_id,outlet_id,version,updated_at FROM sys_user WHERE id=? AND role='TECH_ADMIN' AND is_active=1 AND deleted_at IS NULL AND (branch_id IS NULL OR branch_id=?) AND (outlet_id IS NULL OR outlet_id=?)",responsible,n(pool.get("branchId")),n(pool.get("outletId")));
      if(tech==null)throw problem(400,"RESPONSIBLE_TECH_INVALID","负责科技管理员不存在、已停用或无该网点权限。");
    }
    String[] switches=PhaseFourService.normalizeSwitchFields(raw(b,"switchIp"),raw(b,"switchPort"));
    Map<String,Object> normalized=map("assetId",assetId,"assetType",asset.get("assetType"),"assetName",asset.get("name"),"requiresUser",asset.get("requiresUser"),"requiresResourceCode",asset.get("requiresResourceCode"),"assetCode",code,"userName",userName,"userUnifiedAuthId",userAuth,"responsibleTechUserId",responsible,"switchIp",switches[0],"switchPort",switches[1],"macAddress",PhaseFourService.normalizeMac(raw(b,"macAddress")),"serialNumber",PhaseFourService.normalizeSerialNumber(raw(b,"serialNumber")),"note",optional(b,"note",500));
    Map<String,Object> existing=null;
    if(pool.get("assignmentId")!=null){
      existing=one(c,"SELECT id,assignment_source AS source,manual_asset_id AS assetId,manual_asset_type AS assetType,manual_asset_name AS assetName,manual_requires_user AS requiresUser,manual_requires_resource_code AS requiresResourceCode,resource_code AS assetCode,manual_asset_code AS manualAssetCode,manual_user_name AS userName,manual_user_unified_auth_id AS userUnifiedAuthId,responsible_tech_user_id AS responsibleTechUserId,switch_ip AS switchIp,switch_port AS switchPort,mac_address AS macAddress,serial_number AS serialNumber,manual_note AS note,version FROM ip_assignment WHERE id=? AND status='ASSIGNED'",pool.get("assignmentId"));
      boolean same=existing!=null&&"MANUAL_REGISTRATION".equals(existing.get("source"));
      for(String key:normalized.keySet())if(!sameValue(normalized.get(key),existing==null?null:existing.get(key)))same=false;
      if(existing!=null&&!sameValue(code,existing.get("manualAssetCode")))same=false;
      if(!same)throw problem(409,"IP_ASSIGNMENT_CONFLICT","该 IP 已有不同的有效分配，不能覆盖。");
    }
    if(code!=null){
      Map<String,Object> occupiedCode=one(c,"SELECT id FROM ip_assignment WHERE status='ASSIGNED' AND resource_code=?",code);
      if(occupiedCode!=null&&(existing==null||!sameValue(occupiedCode.get("id"),existing.get("id"))))throw problem(409,"RESOURCE_CODE_DUPLICATE","该资源编码已有有效分配记录。");
    }
    return new PreparedRegistration(pool,asset,normalized,tech,existing,originalSource,source);
  }

  /** Writes an already validated registration without opening or committing a connection. */
  Map<String,Object> write(Connection c,PreparedRegistration prepared,long actor)throws Exception{
    if(prepared.duplicate)throw problem(409,"IP_ALREADY_ASSIGNED","该 IP 已分配，不能重复补录。");
    Map<String,Object> pool=prepared.pool,asset=prepared.asset,v=prepared.normalized;
    String originalSource=prepared.originalSource,now=Instant.now().toString();
    long id=insert(c,"INSERT INTO ip_assignment(application_id,ip_pool_id,resource_code,assigned_by,assigned_at,status,version,switch_port,mac_address,switch_ip,serial_number,assignment_source,manual_asset_id,manual_asset_type,manual_asset_name,manual_requires_user,manual_requires_resource_code,manual_asset_code,manual_user_name,manual_user_unified_auth_id,manual_registered_by,manual_registered_at,responsible_tech_user_id,manual_note,source_pool_state,source_is_active,source_is_reserved,source_reservation_reason) VALUES(NULL,?,?,?,?, 'ASSIGNED',0,?,?,?,?, 'MANUAL_REGISTRATION',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",pool.get("id"),v.get("assetCode"),actor,now,v.get("switchPort"),v.get("macAddress"),v.get("switchIp"),v.get("serialNumber"),asset.get("id"),asset.get("assetType"),asset.get("name"),asset.get("requiresUser"),asset.get("requiresResourceCode"),v.get("assetCode"),v.get("userName"),v.get("userUnifiedAuthId"),actor,now,v.get("responsibleTechUserId"),v.get("note"),prepared.source,"OCCUPIED".equals(originalSource)?1:pool.get("active"),"OCCUPIED".equals(originalSource)?0:pool.get("reserved"),"OCCUPIED".equals(originalSource)?null:pool.get("reservationReason"));
    if("OCCUPIED".equals(originalSource))change(c,"UPDATE ip_address_pool SET is_occupied=0,is_active=1,is_reserved=0,reservation_reason=NULL,updated_at=? WHERE id=? AND is_occupied=1 AND deleted_at IS NULL",now,pool.get("id"));
    audit(c,actor,"MANUAL_REGISTER_IP","IP_ASSIGNMENT",String.valueOf(id),"SUCCESS",json.toJson(map("ipAddress",pool.get("ipAddress"),"cidr",pool.get("cidr"),"sourceState",originalSource,"assetType",asset.get("assetType"),"assetName",asset.get("name"),"responsibleTechUserId",v.get("responsibleTechUserId"),"note",v.get("note"))));
    return map("id",id,"ipAddress",pool.get("ipAddress"),"assignmentSource","MANUAL_REGISTRATION","registeredAt",now,"sourceState",originalSource);
  }

  static final class PreparedRegistration{
    final Map<String,Object> pool,asset,normalized,snapshot;
    final boolean duplicate;
    final String originalSource,source;
    PreparedRegistration(Map<String,Object> p,Map<String,Object> a,Map<String,Object> v,Map<String,Object> tech,Map<String,Object> existing,String original,String s){
      pool=p;asset=a;normalized=v;duplicate=existing!=null;originalSource=original;source=s;
      snapshot=map("pool",p,"asset",a,"normalized",v,"responsible",tech,"existing",existing,"sourceState",original);
    }
  }
  private static boolean sameValue(Object a,Object b){return a instanceof Number&&b instanceof Number?((Number)a).longValue()==((Number)b).longValue():Objects.equals(a,b);}

  Map<String,Object> gatewayOptions(long rangeId)throws Exception{
    try(Connection c=open()){
      Map<String,Object> range=one(c,"SELECT r.id,r.cidr,r.gateway,r.range_type AS rangeType,b.name AS branchName,o.name AS outletName FROM ip_range r JOIN branch b ON b.id=r.branch_id LEFT JOIN outlet o ON o.id=r.outlet_id WHERE r.id=? AND r.deleted_at IS NULL AND b.deleted_at IS NULL AND (o.id IS NULL OR o.deleted_at IS NULL)",rangeId);
      if(range==null)throw problem(404,"IP_RANGE_NOT_FOUND","地址段不存在或已删除。");
      List<Map<String,Object>> items=list(c,"SELECT p.id,p.ip_address AS ipAddress,p.is_active AS active,p.is_reserved AS reserved,p.is_occupied AS occupied,p.reservation_reason AS reservationReason,d.id AS assignmentId FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id LEFT JOIN ip_assignment d ON d.ip_pool_id=p.id AND d.status='ASSIGNED' WHERE p.ip_range_id=? AND p.deleted_at IS NULL ORDER BY p.id",rangeId);
      for(Map<String,Object> item:items){item.put("state",state(item,range.get("gateway")));item.put("eligible",item.get("assignmentId")==null&&n(item.get("occupied"))==0&&!value(item.get("ipAddress")).equals(value(range.get("gateway"))));}
      return map("range",range,"items",items);
    }
  }

  Map<String,Object> adjustGateway(long rangeId,JsonObject b,long actor)throws Exception{
    String target=required(b,"newGateway",45),expected=required(b,"expectedGateway",45),note=optional(b,"note",500);
    validIp(target);
    validIp(expected);
    synchronized(PhaseTwoService.WRITE_LOCK){
      try(Connection c=open()){
        c.setAutoCommit(false);
        try{
          Map<String,Object> range=one(c,"SELECT r.id,r.cidr,r.gateway,r.range_type AS rangeType,b.name AS branchName,o.name AS outletName FROM ip_range r JOIN branch b ON b.id=r.branch_id LEFT JOIN outlet o ON o.id=r.outlet_id WHERE r.id=? AND r.deleted_at IS NULL AND b.deleted_at IS NULL AND (o.id IS NULL OR o.deleted_at IS NULL)",rangeId);
          if(range==null)throw problem(404,"IP_RANGE_NOT_FOUND","地址段不存在或已删除。");
          String old=value(range.get("gateway"));if(!old.equals(expected))throw problem(409,"GATEWAY_STALE","当前网关已被其他管理员更新，请刷新网关选项后重新确认。");if(old.equals(target)){c.commit();return map("changed",false,"gateway",old,"rangeId",rangeId);}
          PhaseFourService.Net net=PhaseFourService.Net.parse(value(range.get("cidr")));if(!net.contains(PhaseFourService.Net.ipv4(target)))throw problem(400,"GATEWAY_OUT_OF_RANGE","新网关必须位于当前地址段内。");
          Map<String,Object> targetPool=one(c,"SELECT p.id,p.ip_address AS ipAddress,p.is_active AS active,p.is_reserved AS reserved,p.is_occupied AS occupied,p.reservation_reason AS reservationReason,d.id AS assignmentId FROM ip_address_pool p LEFT JOIN ip_assignment d ON d.ip_pool_id=p.id AND d.status='ASSIGNED' WHERE p.ip_range_id=? AND p.ip_address=? AND p.deleted_at IS NULL",rangeId,target);
          if(targetPool==null)throw problem(400,"GATEWAY_TARGET_NOT_FOUND","新网关必须是当前地址段中的主机地址。");
          if(n(targetPool.get("occupied"))==1)throw problem(409,"GATEWAY_TARGET_OCCUPIED","新网关已标记为占用，请先恢复为空闲后再调整。");
          if(targetPool.get("assignmentId")!=null)throw problem(409,"GATEWAY_TARGET_ASSIGNED","新网关已被有效分配，请先释放该 IP。");
          Map<String,Object> oldPool=one(c,"SELECT p.id,p.ip_address AS ipAddress,d.id AS assignmentId FROM ip_address_pool p LEFT JOIN ip_assignment d ON d.ip_pool_id=p.id AND d.status='ASSIGNED' WHERE p.ip_range_id=? AND p.ip_address=? AND p.deleted_at IS NULL",rangeId,old);
          if(oldPool==null)throw problem(409,"GATEWAY_CURRENT_NOT_FOUND","当前网关对应的资源池记录不存在。");
          if(oldPool.get("assignmentId")!=null)throw problem(409,"GATEWAY_CURRENT_ASSIGNED","当前网关存在异常有效分配，请先处理后再调整。");
          String now=Instant.now().toString();
          change(c,"UPDATE ip_address_pool SET is_active=1,is_reserved=0,is_occupied=0,reservation_reason=NULL,updated_at=? WHERE id=? AND deleted_at IS NULL",now,oldPool.get("id"));
          change(c,"UPDATE ip_address_pool SET is_active=0,is_reserved=1,is_occupied=0,reservation_reason='网关地址',updated_at=? WHERE id=? AND deleted_at IS NULL",now,targetPool.get("id"));
          try(PreparedStatement p=c.prepareStatement("UPDATE ip_range SET gateway=?,updated_at=? WHERE id=? AND gateway=? AND deleted_at IS NULL")){p.setString(1,target);p.setString(2,now);p.setLong(3,rangeId);p.setString(4,old);if(p.executeUpdate()!=1)throw new PhaseTwoService.Problem(409,"GATEWAY_STALE","当前网关已变化，请刷新后重试。");}
          String targetState=sourceState(targetPool);
          audit(c,actor,"ADJUST_GATEWAY","IP_RANGE",String.valueOf(rangeId),"SUCCESS",json.toJson(map("cidr",range.get("cidr"),"beforeGateway",old,"afterGateway",target,"beforeState","SYSTEM_RESERVED","afterState",targetState,"note",note)));
          c.commit();return map("changed",true,"rangeId",rangeId,"gateway",target,"oldGateway",old,"oldState","AVAILABLE","newState","SYSTEM_RESERVED");
        }catch(Exception e){c.rollback();throw e;}
      }
    }
  }

  private static String state(Map<String,Object> x){return state(x,x.get("gateway"));}
  private static String state(Map<String,Object> x,Object gateway){if(x.get("assignmentId")!=null)return "ASSIGNED";if(value(x.get("ipAddress")).equals(value(gateway)))return "SYSTEM_RESERVED";if(n(x.get("occupied"))==1)return "OCCUPIED";if(n(x.get("reserved"))==1)return "MANUAL_RESERVED";return n(x.get("active"))==1?"AVAILABLE":"DISABLED";}
  private static String sourceState(Map<String,Object> x){String s=state(x);return "AVAILABLE".equals(s)||"OCCUPIED".equals(s)||"DISABLED".equals(s)||"MANUAL_RESERVED".equals(s)?s:null;}
  private Connection open()throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+dbFile);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");}return c;}
  private static Map<String,Object> one(Connection c,String sql,Object...args)throws Exception{try(PreparedStatement p=c.prepareStatement(sql)){bind(p,args);try(ResultSet r=p.executeQuery()){return r.next()?row(r):null;}}}
  private static List<Map<String,Object>> list(Connection c,String sql,Object...args)throws Exception{try(PreparedStatement p=c.prepareStatement(sql)){bind(p,args);try(ResultSet r=p.executeQuery()){List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();while(r.next())out.add(row(r));return out;}}}
  private static Map<String,Object> row(ResultSet r)throws Exception{Map<String,Object> out=new LinkedHashMap<String,Object>();ResultSetMetaData md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++)out.put(md.getColumnLabel(i),r.getObject(i));return out;}
  private static long insert(Connection c,String sql,Object...args)throws Exception{try(PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(p,args);p.executeUpdate();try(ResultSet r=p.getGeneratedKeys()){r.next();return r.getLong(1);}}}
  private static void change(Connection c,String sql,Object...args)throws Exception{try(PreparedStatement p=c.prepareStatement(sql)){bind(p,args);if(p.executeUpdate()!=1)throw problem(404,"RESOURCE_NOT_FOUND","记录不存在或已被处理。");}}
  private static boolean exists(Connection c,String sql,Object...args)throws Exception{return one(c,sql,args)!=null;}
  private static void bind(PreparedStatement p,Object...args)throws Exception{for(int i=0;i<args.length;i++){Object x=args[i];if(x==null)p.setNull(i+1,Types.VARCHAR);else if(x instanceof Long)p.setLong(i+1,(Long)x);else if(x instanceof Integer)p.setInt(i+1,(Integer)x);else p.setString(i+1,String.valueOf(x));}}
  private static String raw(JsonObject b,String key){return !b.has(key)||b.get(key).isJsonNull()?null:b.get(key).getAsString();}
  private static String optional(JsonObject b,String key,int max){if(!b.has(key)||b.get(key).isJsonNull())return null;String v=b.get(key).getAsString().trim();if(v.isEmpty())return null;if(v.length()>max)throw problem(400,"FIELD_INVALID",key+" 格式不正确。");return v;}
  private static String required(JsonObject b,String key,int max){String v=optional(b,key,max);if(v==null)throw problem(400,"FIELD_REQUIRED",key+" 为必填项。");return v;}
  private static long number(JsonObject b,String key){try{return b.get(key).getAsLong();}catch(Exception e){throw problem(400,"FIELD_INVALID",key+" 必须是编号。");}}
  private static Long optionalLong(JsonObject b,String key){if(!b.has(key)||b.get(key).isJsonNull()||b.get(key).getAsString().trim().isEmpty())return null;try{return Long.valueOf(b.get(key).getAsLong());}catch(Exception e){throw problem(400,"FIELD_INVALID",key+" 必须是编号。");}}
  private static long n(Object x){return x==null?0:((Number)x).longValue();}
  private static String value(Object x){return x==null?"":String.valueOf(x);}
  private static void validIp(String text){String[] parts=text.split("\\.");if(parts.length!=4)throw problem(400,"IP_INVALID","IP 地址格式不正确。");for(String part:parts)try{int n=Integer.parseInt(part);if(n<0||n>255)throw new Exception();}catch(Exception e){throw problem(400,"IP_INVALID","IP 地址格式不正确。");}}
  private static PhaseTwoService.Problem problem(int status,String code,String message){return new PhaseTwoService.Problem(status,code,message);}
  private static void audit(Connection c,long actor,String action,String type,String target,String result,String detail)throws Exception{try(PreparedStatement p=c.prepareStatement("INSERT INTO audit_log(actor_user_id,action,target_type,target_id,result,detail_json) VALUES(?,?,?,?,?,?)")){p.setLong(1,actor);p.setString(2,action);p.setString(3,type);p.setString(4,target);p.setString(5,result);p.setString(6,detail);p.executeUpdate();}}
  private static Map<String,Object> map(Object...v){Map<String,Object>x=new LinkedHashMap<String,Object>();for(int i=0;i<v.length;i+=2)x.put(String.valueOf(v[i]),v[i+1]);return x;}
}
