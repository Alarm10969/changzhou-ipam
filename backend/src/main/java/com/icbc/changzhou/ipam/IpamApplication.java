package com.icbc.changzhou.ipam;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.mindrot.jbcrypt.BCrypt;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

/** Stage-one, Java-11-only application server. It has no build-time/runtime Maven, Node, MySQL, or Nginx dependency. */
public final class IpamApplication {
  private static final Gson JSON = new Gson();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int SESSION_HOURS = 8;
  private final Database db;
  private final PhaseTwoService phaseTwo;
  private final PhaseThreeService phaseThree;
  private final PhaseFourService phaseFour;
  private final OutletDirectoryService outletDirectory;
  private final Path staticDir;
  private final boolean secureCookie;

  private IpamApplication(Database db, Path staticDir, boolean secureCookie) throws IOException { this.db = db; this.phaseTwo = new PhaseTwoService(db.file); this.phaseThree = new PhaseThreeService(db.file); this.phaseFour = new PhaseFourService(db.file); this.outletDirectory = new OutletDirectoryService(db.file); this.staticDir = staticDir; this.secureCookie = secureCookie; }

  public static void main(String[] args) throws Exception {
    Path data = Paths.get(env("IPAM_DATA_DIR", "data")).toAbsolutePath().normalize();
    Files.createDirectories(data);
    if (!Files.isWritable(data)) throw new IllegalStateException("数据目录不可写: " + data);
    Database db = new Database(data.resolve("ipam.db"));
    db.migrate(); db.bootstrapAdmin(System.getenv("IPAM_ADMIN_USERNAME"), bootstrapPassword());
    Path staticDir = Paths.get(env("IPAM_STATIC_DIR", "frontend/dist")).toAbsolutePath().normalize();
    int port = Integer.parseInt(env("IPAM_PORT", "18080"));
    HttpServer server = HttpServer.create(new InetSocketAddress(env("IPAM_BIND", "127.0.0.1"), port), 64);
    server.createContext("/", new IpamApplication(db, staticDir, Boolean.parseBoolean(env("IPAM_SECURE_COOKIE", "false")))::handle);
    server.setExecutor(Executors.newFixedThreadPool(12)); server.start();
    System.out.println("IPAM stage-4 is listening on http://" + env("IPAM_BIND", "127.0.0.1") + ":" + port + ", database=" + data.resolve("ipam.db"));
  }

  private void handle(HttpExchange x) throws IOException {
    String requestId = UUID.randomUUID().toString();
    try {
      String path = x.getRequestURI().getPath();
      if (path.startsWith("/api/v1/")) api(x, requestId, path.substring(8)); else serveStatic(x, path);
    } catch (ApiError e) { respond(x, e.status, error(e.code, e.getMessage(), requestId)); }
    catch (PhaseTwoService.Problem e) { respond(x,e.status,error(e.code,e.getMessage(),requestId)); }
    catch (Exception e) { e.printStackTrace(System.err); respond(x, 500, error("INTERNAL_ERROR", "系统内部错误，请联系管理员。", requestId)); }
  }

  private void api(HttpExchange x, String requestId, String path) throws Exception {
    String method = x.getRequestMethod();
    if ("auth/login".equals(path) && "POST".equals(method)) { login(x, requestId); return; }
    if ("health".equals(path) && "GET".equals(method)) { respond(x, 200, map("status","UP")); return; }
    Principal p = authenticate(x);
    if (!"GET".equals(method) && !"HEAD".equals(method)) verifyCsrf(x, p);
    if ("auth/me".equals(path) && "GET".equals(method)) { respond(x, 200, p.publicView()); return; }
    if ("auth/logout".equals(path) && "POST".equals(method)) { db.revokeSession(p.sessionId); audit(p, requestId,"LOGOUT","SESSION",String.valueOf(p.sessionId),"SUCCESS",null); expireCookie(x); respond(x,204,null); return; }
    if ("auth/change-password".equals(path) && "POST".equals(method)) { changePassword(x,p,requestId); return; }
    if (p.mustChangePassword) throw new ApiError(403,"PASSWORD_CHANGE_REQUIRED","请先修改密码后继续使用业务功能。");
    if ("my-assignments".equals(path) && "GET".equals(method)) { if(!"TECH_ADMIN".equals(p.role))throw new ApiError(403,"FORBIDDEN","仅科技管理员可查询本人 IP。"); respond(x,200,map("items",phaseThree.myAssignments(p.id))); return; }
    if (path.matches("my-assignments/\\d+/release") && "POST".equals(method)) { if(!"TECH_ADMIN".equals(p.role))throw new ApiError(403,"FORBIDDEN","仅科技管理员可释放本人 IP。"); JsonObject b=body(x); respond(x,200,phaseThree.release(parseId(path),p.id,optional(b,"reason",500),requestId)); return; }
    if ("notifications".equals(path) && "GET".equals(method)) { respond(x,200,map("items",phaseThree.notifications(p.id))); return; }
    if (path.matches("notifications/\\d+/read") && "POST".equals(method)) { phaseThree.markRead(parseId(path),p.id); respond(x,204,null); return; }
    if ("applications".equals(path) && "GET".equals(method)) { if(!"TECH_ADMIN".equals(p.role))throw new ApiError(403,"FORBIDDEN","仅科技管理员可查询本人申请。"); respond(x,200,map("items",phaseFour.applicationsForApplicant(null,null,Long.valueOf(p.id)))); return; }
    if ("applications".equals(path) && "POST".equals(method)) { if(!"TECH_ADMIN".equals(p.role))throw new ApiError(403,"FORBIDDEN","仅科技管理员可提交申请。"); long id=phaseTwo.submitApplication(p.id,body(x)); respond(x,201,map("id",id,"status","PENDING")); return; }
    if ("applications-v2".equals(path) && "POST".equals(method)) { if(!"TECH_ADMIN".equals(p.role))throw new ApiError(403,"FORBIDDEN","仅科技管理员可提交申请。"); long id=phaseFour.submitApplication(p.id,body(x)); respond(x,201,map("id",id,"status","PENDING")); return; }
    if ("application-options".equals(path) && "GET".equals(method)) { if(!"TECH_ADMIN".equals(p.role))throw new ApiError(403,"FORBIDDEN","仅科技管理员可查询申请选项。"); respond(x,200,phaseFour.applicationOptions(Long.parseLong(query(x,"outletId")))); return; }
    if ("asset-categories".equals(path) && "GET".equals(method)) { respond(x,200,map("items",phaseFour.categories(query(x,"assetType")))); return; }
    if ("assets".equals(path) && "GET".equals(method)) { respond(x,200,map("items",phaseFour.assets(query(x,"assetType"),false))); return; }
    if ("outlets".equals(path) && "GET".equals(method)) { respond(x,200,map("items",db.rows("SELECT o.id,o.branch_id AS branchId,o.code,o.name FROM outlet o JOIN branch b ON b.id=o.branch_id WHERE o.is_active=1 AND o.deleted_at IS NULL AND b.is_active=1 AND b.deleted_at IS NULL ORDER BY o.branch_id,o.name"))); return; }
    if ("branches".equals(path) && "GET".equals(method)) { respond(x,200,map("items",db.rows("SELECT id,code,name,is_active AS active FROM branch WHERE deleted_at IS NULL ORDER BY name"))); return; }
    if (path.startsWith("admin/")) { requireAdmin(p); admin(x,p,requestId,path.substring(6)); return; }
    throw new ApiError(404,"NOT_FOUND","接口不存在。");
  }

  private void login(HttpExchange x, String requestId) throws Exception {
    JsonObject b = body(x); String username = required(b,"username",64); String password = required(b,"password",128);
    Database.Login result = db.login(username,password);
    if (result == null) { audit(null,requestId,"LOGIN","USER",username,"FAILURE",null); throw new ApiError(401,"LOGIN_FAILED","账号或密码错误，或账号暂时不可用。"); }
    Session s = db.createSession(result.user.id);
    setCookie(x,s.token); audit(result.user,requestId,"LOGIN","USER",String.valueOf(result.user.id),"SUCCESS",null);
    Map<String,Object> out = result.user.publicView(); out.put("csrfToken",s.csrf); respond(x,200,out);
  }

  private void changePassword(HttpExchange x, Principal p, String requestId) throws Exception {
    JsonObject b=body(x); String current=required(b,"currentPassword",128); String next=required(b,"newPassword",128);
    validatePassword(next);
    if (!BCrypt.checkpw(current,p.passwordHash)) { audit(p,requestId,"CHANGE_PASSWORD","USER",String.valueOf(p.id),"FAILURE",null); throw new ApiError(400,"CURRENT_PASSWORD_INVALID","当前密码不正确。"); }
    if (BCrypt.checkpw(next,p.passwordHash)) throw new ApiError(400,"PASSWORD_REUSED","新密码不能与当前密码相同。");
    Session replacement=db.changePassword(p.id,next);
    audit(p,requestId,"CHANGE_PASSWORD","USER",String.valueOf(p.id),"SUCCESS",null); setCookie(x,replacement.token);
    Map<String,Object> out=p.publicView(); out.put("mustChangePassword",false); out.put("csrfToken",replacement.csrf); out.put("message","密码已修改，其他登录会话已失效。"); respond(x,200,out);
  }

  private void admin(HttpExchange x, Principal p, String requestId, String path) throws Exception {
    String m=x.getRequestMethod();
    if ("users".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.users())); return; }
    if ("users".equals(path) && "POST".equals(m)) { createUser(x,p,requestId); return; }
    if (path.matches("users/\\d+/reset-password") && "POST".equals(m)) { resetPassword(x,p,requestId,parseId(path)); return; }
    if (path.matches("users/\\d+/active") && "PUT".equals(m)) { activeUser(x,p,requestId,parseId(path)); return; }
    if (path.matches("users/\\d+") && "DELETE".equals(m)) { deleteUser(x,p,requestId,parseId(path)); return; }
    if ("dashboard".equals(path) && "GET".equals(m)) { respond(x,200,phaseTwo.dashboard()); return; }
    if ("settings".equals(path) && "GET".equals(m)) { respond(x,200,phaseFour.settings()); return; }
    if ("settings".equals(path) && "PUT".equals(m)) { respond(x,200,phaseFour.saveRule(body(x),p.id)); return; }
    if ("settings/history".equals(path) && "GET".equals(m)) { respond(x,200,map("items",phaseFour.ruleHistory())); return; }
    if ("asset-categories".equals(path) && "GET".equals(m)) { respond(x,200,map("items",phaseFour.categories(query(x,"assetType"),"1".equals(query(x,"includeInactive"))))); return; }
    if ("assets".equals(path) && "GET".equals(m)) { respond(x,200,map("items",phaseFour.assets(query(x,"assetType"),"1".equals(query(x,"includeInactive"))))); return; }
    if ("assets".equals(path) && "POST".equals(m)) { respond(x,201,phaseFour.createAsset(body(x),p.id)); return; }
    if (path.matches("assets/\\d+") && "PUT".equals(m)) { phaseFour.updateAsset(parseId(path),body(x),p.id); respond(x,204,null); return; }
    if (path.matches("assets/\\d+") && "DELETE".equals(m)) { phaseFour.deleteAsset(parseId(path),p.id); respond(x,204,null); return; }
    if ("asset-categories".equals(path) || "asset-subcategories".equals(path) || "asset-mappings".equals(path) || path.matches("asset-categories/\\d+") || path.matches("asset-subcategories/\\d+")) throw new ApiError(410,"LEGACY_ASSET_MODEL_DISABLED","旧分类、小类和映射模型已停用，请使用资产管理中的直接资产定义。");
    if ("outlet-plans/preview".equals(path) && "POST".equals(m)) { respond(x,200,phaseFour.previewPlan(body(x))); return; }
    if ("outlet-plans".equals(path) && "POST".equals(m)) { respond(x,201,phaseFour.createPlan(body(x),p.id)); return; }
    if (path.matches("outlet-plan-template\\.(csv|xlsx)") && "GET".equals(m)) { String f=path.endsWith(".csv")?"CSV":"XLSX"; binary(x,200,phaseFour.planTemplate(f),"CSV".equals(f)?"text/csv; charset=utf-8":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","attachment; filename=ipam-outlet-six-network-template."+f.toLowerCase()); return; }
    if ("outlet-plan-import".equals(path) && "POST".equals(m)) { Upload u=upload(x); respond(x,201,phaseFour.previewPlanImport(u.fileName,u.bytes,p.id)); return; }
    if (path.matches("outlet-plan-import/\\d+/confirm") && "POST".equals(m)) { respond(x,200,phaseFour.confirmPlanImport(parseId(path),p.id)); return; }
    if ("resource-overview".equals(path) && "GET".equals(m)) { respond(x,200,phaseThree.overview(query(x,"branchId"),query(x,"outletId"),parsePage(query(x,"branchPage")),parsePage(query(x,"outletPage")))); return; }
    if ("release-history".equals(path) && "GET".equals(m)) { respond(x,200,map("items",phaseThree.releases(query(x,"q"),query(x,"branch"),query(x,"outlet"),query(x,"assetType"),query(x,"from"),query(x,"to")))); return; }
    if ("exports".equals(path) && "POST".equals(m)) { JsonObject b=body(x); String type=required(b,"type",32),format=required(b,"format",8); Set<String> fields=new LinkedHashSet<>();if(b.has("fields")&&b.get("fields").isJsonArray())for(JsonElement e:b.getAsJsonArray("fields"))fields.add(e.getAsString());Map<String,String> filters=new LinkedHashMap<>();if(b.has("filters")&&b.get("filters").isJsonObject())for(Map.Entry<String,JsonElement> e:b.getAsJsonObject("filters").entrySet())filters.put(e.getKey(),e.getValue().getAsString()); byte[] data=phaseThree.export(type,format,fields,filters,p.id);String ext="XLSX".equals(format)?"xlsx":"csv";binary(x,200,data,"XLSX".equals(format)?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"text/csv; charset=utf-8","attachment; filename=ipam-"+type.toLowerCase()+"-"+Instant.now().toEpochMilli()+"."+ext); return; }
    if ("applications".equals(path) && "GET".equals(m)) { respond(x,200,map("items",phaseFour.applications(query(x,"status"),query(x,"q")))); return; }
    if (path.matches("applications/\\d+/reject") && "POST".equals(m)) { phaseTwo.reject(parseId(path),p.id,required(body(x),"reason",500)); audit(p,requestId,"REJECT_APPLICATION","APPLICATION",String.valueOf(parseId(path)),"SUCCESS",null); respond(x,204,null); return; }
    if (path.matches("applications/\\d+/approve") && "POST".equals(m)) { JsonObject b=body(x); phaseFour.approve(parseId(path),number(b,"poolId"),optional(b,"finalRangeType",32),optional(b,"approvalOpinion",500),optional(b,"overrideReason",500),optionalSwitchPort(b),p.id); respond(x,204,null); return; }
    if (path.matches("applications/\\d+/candidates") && "GET".equals(m)) { respond(x,200,phaseFour.candidates(parseId(path),query(x,"rangeType"),query(x,"q"),parsePage(query(x,"page")))); return; }
    if (path.matches("assignments/\\d+/switch-port") && "PUT".equals(m)) { JsonObject b=body(x); String versionKey=b.has("assignmentVersion")?"assignmentVersion":"version"; respond(x,200,phaseFour.updateSwitchPortByAssignment(parseId(path),number(b,"poolId"),number(b,versionKey),optionalSwitchPort(b),p.id)); return; }
    if (path.matches("assignments/\\d+/release") && "POST".equals(m)) { JsonObject b=body(x); respond(x,200,phaseThree.forceRelease(parseId(path),p.id,optional(b,"reason",500),requestId)); return; }
    if ("available-ips".equals(path) && "GET".equals(m)) { long applicationId=Long.parseLong(query(x,"applicationId")); int page=parsePage(query(x,"page")); respond(x,200,phaseTwo.availableIpPage(applicationId,query(x,"q"),page)); return; }
    if (path.matches("import-templates/(OUTLET|IP_RANGE|IP_POOL)\\.(csv|xlsx)") && "GET".equals(m)) { String[] a=path.substring("import-templates/".length()).split("\\."); binary(x,200,phaseTwo.template(a[0],a[1].equals("csv")?"CSV":"XLSX"),a[1].equals("csv")?"text/csv; charset=utf-8":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","attachment; filename=ipam-"+a[0].toLowerCase()+"."+a[1]); return; }
    if (path.matches("imports/(OUTLET|IP_RANGE|IP_POOL)") && "POST".equals(m)) { Upload u=upload(x); respond(x,201,phaseTwo.preview(path.substring(8),u.fileName,u.bytes,p.id)); return; }
    if (path.matches("imports/\\d+/confirm") && "POST".equals(m)) { respond(x,200,phaseTwo.confirm(parseId(path),p.id)); return; }
    if (path.matches("imports/\\d+") && "DELETE".equals(m)) { phaseTwo.cancel(parseId(path),p.id); respond(x,204,null); return; }
    if (path.matches("imports/\\d+/errors") && "GET".equals(m)) { respond(x,200,map("items",phaseTwo.importErrors(parseId(path),p.id))); return; }
    if (path.matches("imports/\\d+/error-report.csv") && "GET".equals(m)) { long id=parseId(path); binary(x,200,phaseTwo.importErrorReport(id,p.id),"text/csv; charset=utf-8","attachment; filename=ipam-import-"+id+"-errors.csv"); return; }
    if ("imports/recent".equals(path) && "GET".equals(m)) { respond(x,200,map("items",phaseTwo.recentImports(p.id))); return; }
    if ("network-defaults".equals(path) && "GET".equals(m)) { respond(x,200,phaseTwo.networkDefaults()); return; }
    if ("network-defaults".equals(path) && "PUT".equals(m)) { phaseTwo.updateNetworkDefaults(body(x),p.id); respond(x,204,null); return; }
    if ("outlet-directory".equals(path) && "GET".equals(m)) { respond(x,200,map("items",outletDirectory.list(query(x,"branchId"),query(x,"q")))); return; }
    if (path.matches("outlet-directory/\\d+") && "PUT".equals(m)) { outletDirectory.update(parseId(path),body(x),p.id); respond(x,204,null); return; }
    if ("outlet-directory/template.xlsx".equals(path) && "GET".equals(m)) { binary(x,200,outletDirectory.template(),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","attachment; filename=ipam-outlet-directory-template.xlsx"); return; }
    if ("outlet-directory/import".equals(path) && "POST".equals(m)) { Upload u=upload(x); respond(x,200,outletDirectory.importXlsx(u.fileName,u.bytes,p.id)); return; }
    if ("branches".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.rows("SELECT b.id,b.code,b.name,b.is_active AS active,(SELECT count(*) FROM outlet o WHERE o.branch_id=b.id AND o.deleted_at IS NULL) AS outletCount,(SELECT count(*) FROM ip_range r WHERE r.branch_id=b.id AND r.deleted_at IS NULL) AS rangeCount,(SELECT count(*) FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id WHERE r.branch_id=b.id AND r.deleted_at IS NULL AND p.deleted_at IS NULL) AS poolCount,(SELECT count(*) FROM ip_application x WHERE x.branch_id=b.id AND x.status='PENDING') AS pendingCount,(SELECT count(*) FROM ip_assignment a JOIN ip_application x ON x.id=a.application_id WHERE x.branch_id=b.id) AS assignmentCount FROM branch b WHERE b.deleted_at IS NULL ORDER BY b.name"))); return; }
    if ("branches".equals(path) && "POST".equals(m)) { createBranch(x,p,requestId); return; }
    if (path.matches("branches/\\d+") && "PUT".equals(m)) { updateBranch(x,p,requestId,parseId(path)); return; }
    if (path.matches("branches/\\d+") && "DELETE".equals(m)) { respond(x,200,phaseTwo.deleteBranch(parseId(path),p.id)); return; }
    if ("outlets".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.rows("SELECT o.id,o.branch_id AS branchId,b.name AS branchName,o.code,o.name,o.is_active AS active,(SELECT r.id FROM ip_range r WHERE r.outlet_id=o.id AND r.deleted_at IS NULL ORDER BY r.id LIMIT 1) AS rangeId,(SELECT r.cidr FROM ip_range r WHERE r.outlet_id=o.id AND r.deleted_at IS NULL ORDER BY r.id LIMIT 1) AS cidr,(SELECT count(*) FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id WHERE r.outlet_id=o.id AND r.deleted_at IS NULL AND p.deleted_at IS NULL) AS poolCount,(SELECT count(*) FROM ip_application x WHERE x.outlet_id=o.id AND x.status='PENDING') AS pendingCount,(SELECT count(*) FROM ip_assignment a JOIN ip_application x ON x.id=a.application_id WHERE x.outlet_id=o.id) AS assignmentCount FROM outlet o JOIN branch b ON b.id=o.branch_id WHERE o.deleted_at IS NULL AND b.deleted_at IS NULL ORDER BY b.name,o.name"))); return; }
    if ("outlets".equals(path) && "POST".equals(m)) { createOutlet(x,p,requestId); return; }
    if (path.matches("outlets/\\d+") && "PUT".equals(m)) { phaseTwo.updateOutlet(parseId(path),body(x),p.id); respond(x,204,null); return; }
    if (path.matches("outlets/\\d+") && "DELETE".equals(m)) { respond(x,200,phaseTwo.deleteOutlet(parseId(path),p.id)); return; }
    if ("ip-ranges".equals(path) && "GET".equals(m)) { respond(x,200,map("items",phaseTwo.ranges())); return; }
    if ("ip-ranges".equals(path) && "POST".equals(m)) { long id=phaseTwo.createRange(body(x),p.id); respond(x,201,map("id",id)); return; }
    if (path.matches("ip-ranges/\\d+") && "PUT".equals(m)) { phaseTwo.updateRange(parseId(path),body(x),p.id); respond(x,204,null); return; }
    if ("ip-pool".equals(path) && "GET".equals(m)) { respond(x,200,phaseTwo.poolItems(optionalQueryId(query(x,"branchId"),"支行"),optionalQueryId(query(x,"outletId"),"网点"),query(x,"q"),parsePage(query(x,"page")))); return; }
    if ("ip-pool-v2".equals(path) && "GET".equals(m)) { respond(x,200,phaseFour.pool(optionalQueryId(query(x,"branchId"),"支行"),optionalQueryId(query(x,"outletId"),"网点"),query(x,"rangeType"),query(x,"state"),query(x,"q"),parsePage(query(x,"page")))); return; }
    if ("ip-pool".equals(path) && "POST".equals(m)) { throw new ApiError(409,"IP_POOL_AUTOMATIC","IP 资源池由网点绑定地址段后自动生成，请直接维护具体 IP 的可用状态。"); }
    if ("ip-pool/status".equals(path) && "PUT".equals(m)) { int count=phaseTwo.updatePoolBatch(body(x),p.id); respond(x,200,map("updated",count)); return; }
    if (path.matches("ip-pool/\\d+/switch-port") && "PUT".equals(m)) { JsonObject b=body(x); String versionKey=b.has("assignmentVersion")?"assignmentVersion":"version"; respond(x,200,phaseFour.updateSwitchPort(parseId(path),number(b,"assignmentId"),number(b,versionKey),optionalSwitchPort(b),p.id)); return; }
    if (path.matches("ip-pool/\\d+") && "PUT".equals(m)) { updatePool(x,p,requestId,parseId(path)); return; }
    if (path.matches("ip-pool/\\d+/reachability") && "POST".equals(m)) { respond(x,200,phaseFour.ping(parseId(path),p.id)); return; }
    if ("audit".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.auditRows())); return; }
    throw new ApiError(404,"NOT_FOUND","管理接口不存在。");
  }

  private void createUser(HttpExchange x, Principal p,String r) throws Exception { JsonObject b=body(x); String requested=optional(b,"role",32); if(requested==null)requested="TECH_ADMIN"; if(!"TECH_ADMIN".equals(requested)&&!"ADMIN".equals(requested)) throw new ApiError(400,"ROLE_INVALID","角色必须是管理员或科技管理员。"); String username=required(b,"username",64),password=required(b,"initialPassword",128); validatePassword(password); if(db.visibleUsernameExists(username))throw new ApiError(409,"USERNAME_DUPLICATE","该登录名已存在，请使用其他登录名。"); long id=db.createUser(username,required(b,"realName",100),password,requested,p.id); audit(p,r,"CREATE_USER","USER",String.valueOf(id),"SUCCESS",map("role",requested)); respond(x,201,map("id",id,"mustChangePassword",true)); }
  private void resetPassword(HttpExchange x,Principal p,String r,long id) throws Exception { String password=required(body(x),"newPassword",128); validatePassword(password); db.resetPassword(id,password); audit(p,r,"RESET_PASSWORD","USER",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }
  private void activeUser(HttpExchange x,Principal p,String r,long id) throws Exception { boolean active=bool(body(x),"active"); if(id==p.id&&!active) throw new ApiError(400,"SELF_DISABLE","不能禁用当前管理员账号。"); db.setUserActive(id,active); audit(p,r,active?"ENABLE_USER":"DISABLE_USER","USER",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }
  private void deleteUser(HttpExchange x,Principal p,String r,long id) throws Exception { if(id==p.id)throw new ApiError(400,"SELF_DELETE","不能删除当前管理员账号。"); db.deleteUser(id); audit(p,r,"DELETE_USER","USER",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }
  private void createBranch(HttpExchange x,Principal p,String r) throws Exception { JsonObject b=body(x); String code=required(b,"code",32),name=required(b,"name",100); if(db.visibleBranchCodeExists(code))throw new ApiError(409,"BRANCH_CODE_DUPLICATE","该支行号已存在，请勿重复新增。"); long id=db.insert("INSERT INTO branch(code,name) VALUES(?,?)",code,name); audit(p,r,"CREATE_BRANCH","BRANCH",String.valueOf(id),"SUCCESS",null); respond(x,201,map("id",id)); }
  private void updateBranch(HttpExchange x,Principal p,String r,long id) throws Exception { JsonObject b=body(x); boolean active=bool(b,"active"); if(!active&&!db.rows("SELECT a.id FROM ip_assignment a JOIN ip_application x ON x.id=a.application_id WHERE x.branch_id="+id+" LIMIT 1").isEmpty())throw new ApiError(409,"BRANCH_ASSIGNED_LOCKED","支行存在有效分配，不能停用；请先按流程回收相关 IP。"); db.change("UPDATE branch SET code=?,name=?,is_active=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND deleted_at IS NULL",required(b,"code",32),required(b,"name",100),active?1:0,id); audit(p,r,"UPDATE_BRANCH","BRANCH",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }
  private void createOutlet(HttpExchange x,Principal p,String r) throws Exception { JsonObject b=body(x); long branchId=number(b,"branchId"); String code=required(b,"code",32),name=required(b,"name",100); db.requireActiveBranch(branchId); if(db.visibleOutletCodeExists(branchId,code))throw new ApiError(409,"OUTLET_CODE_DUPLICATE","该支行下已存在相同网点编号，请勿重复新增。"); long id=db.insert("INSERT INTO outlet(branch_id,code,name) VALUES(?,?,?)",branchId,code,name); audit(p,r,"CREATE_OUTLET","OUTLET",String.valueOf(id),"SUCCESS",null); respond(x,201,map("id",id)); }
  private void updatePool(HttpExchange x,Principal p,String r,long id) throws Exception { phaseTwo.updatePool(id,body(x),p.id); respond(x,204,null); }

  private Principal authenticate(HttpExchange x) throws Exception { String token=cookie(x,"IPAM_SESSION"); if(token==null) throw new ApiError(401,"AUTH_REQUIRED","请先登录。"); Principal p=db.session(token); if(p==null) throw new ApiError(401,"SESSION_INVALID","会话已失效，请重新登录。"); return p; }
  private void verifyCsrf(HttpExchange x,Principal p) { String csrf=x.getRequestHeaders().getFirst("X-CSRF-Token"); if(csrf==null||!constantEquals(hash(csrf),p.csrfHash)) throw new ApiError(403,"CSRF_INVALID","请求安全校验失败，请刷新页面后重试。"); String origin=x.getRequestHeaders().getFirst("Origin"); String referer=x.getRequestHeaders().getFirst("Referer"); String host=x.getRequestHeaders().getFirst("Host"); if(origin!=null&&!origin.isEmpty()&&!origin.endsWith("://"+host)) throw new ApiError(403,"ORIGIN_INVALID","跨站请求被拒绝。"); if(origin==null&&referer!=null&&!referer.startsWith("http://"+host)&&!referer.startsWith("https://"+host)) throw new ApiError(403,"ORIGIN_INVALID","跨站请求被拒绝。"); }
  private void requireAdmin(Principal p) { if(!"ADMIN".equals(p.role)) throw new ApiError(403,"FORBIDDEN","您没有管理端访问权限。"); }
  private void audit(Principal p,String requestId,String action,String type,String target,String result,Object detail) throws Exception { db.audit(p==null?null:p.id,requestId,action,type,target,result,detail==null?null:JSON.toJson(detail)); }
  private void serveStatic(HttpExchange x,String path) throws IOException { if(!"GET".equals(x.getRequestMethod())&& !"HEAD".equals(x.getRequestMethod())) { respond(x,405,map("code","METHOD_NOT_ALLOWED")); return; } Path p=staticDir.resolve(path.equals("/")?"index.html":path.substring(1)).normalize(); if(!p.startsWith(staticDir)||!Files.isRegularFile(p)) p=staticDir.resolve("index.html"); if(!Files.isRegularFile(p)){ respond(x,503,map("code","FRONTEND_NOT_BUILT","message","前端静态文件尚未构建。")); return;} String type=p.toString().endsWith(".js")?"application/javascript; charset=utf-8":p.toString().endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8"; byte[] bytes=Files.readAllBytes(p); x.getResponseHeaders().set("Content-Type",type); x.getResponseHeaders().set("X-Content-Type-Options","nosniff"); x.getResponseHeaders().set("Cache-Control","no-store"); x.sendResponseHeaders(200,"HEAD".equals(x.getRequestMethod())?-1:bytes.length); if(!"HEAD".equals(x.getRequestMethod())) try(OutputStream o=x.getResponseBody()){o.write(bytes);} }
  private static JsonObject body(HttpExchange x) throws IOException { try(Reader r=new InputStreamReader(x.getRequestBody(),StandardCharsets.UTF_8)){JsonElement e=JsonParser.parseReader(r);if(!e.isJsonObject())throw new ApiError(400,"INVALID_BODY","请求内容必须是 JSON 对象。");return e.getAsJsonObject();}catch(com.google.gson.JsonParseException e){throw new ApiError(400,"INVALID_JSON","请求 JSON 格式不正确。");} }
  private static String required(JsonObject b,String key,int max){String v=optional(b,key,max);if(v==null)throw new ApiError(400,"VALIDATION_ERROR",key+" 为必填项。");return v;}
  private static String optional(JsonObject b,String key,int max){if(!b.has(key)||b.get(key).isJsonNull())return null;String v=b.get(key).getAsString().trim();if(v.isEmpty())return null;if(v.length()>max)throw new ApiError(400,"VALIDATION_ERROR",key+" 格式不正确。");return v;}
  private static String optionalSwitchPort(JsonObject b){if(!b.has("switchPort")||b.get("switchPort").isJsonNull())return null;return PhaseFourService.normalizeSwitchPort(b.get("switchPort").getAsString());}
  private static long number(JsonObject b,String key){try{return b.get(key).getAsLong();}catch(Exception e){throw new ApiError(400,"VALIDATION_ERROR",key+" 必须是有效编号。");}}
  private static boolean bool(JsonObject b,String key){try{return b.get(key).getAsBoolean();}catch(Exception e){throw new ApiError(400,"VALIDATION_ERROR",key+" 必须是布尔值。");}}
  private static int parsePage(String value){try{return value==null?1:Math.max(1,Integer.parseInt(value));}catch(Exception e){throw new ApiError(400,"VALIDATION_ERROR","页码不正确。");}}
  private static Long optionalQueryId(String value,String label){if(value==null||value.trim().isEmpty())return null;try{long id=Long.parseLong(value);if(id<=0)throw new NumberFormatException();return Long.valueOf(id);}catch(Exception e){throw new ApiError(400,"VALIDATION_ERROR",label+"编号不正确。");}}
  private static String query(HttpExchange x,String name){String q=x.getRequestURI().getRawQuery();if(q==null)return null;for(String item:q.split("&")){String[] a=item.split("=",2);if(a.length>0&&name.equals(a[0]))try{return java.net.URLDecoder.decode(a.length==2?a[1]:"", "UTF-8");}catch(Exception e){throw new ApiError(400,"VALIDATION_ERROR","查询参数不正确。");}}return null;}
  private static long parseId(String path){String[] a=path.split("/");return Long.parseLong(a[1]);}
  private static void validatePassword(String v){String message=PasswordPolicy.validationMessage(v);if(message!=null)throw new ApiError(400,"PASSWORD_POLICY",message);}
  private static String maskFromCidr(String cidr){int n=Integer.parseInt(cidr.substring(cidr.indexOf('/')+1));long mask=n==0?0:(0xffffffffL << (32-n))&0xffffffffL;return ((mask>>>24)&255)+"."+((mask>>>16)&255)+"."+((mask>>>8)&255)+"."+(mask&255);}
  private static void respond(HttpExchange x,int status,Object body) throws IOException {x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");if(status==204){x.sendResponseHeaders(204,-1);return;}byte[] bytes=JSON.toJson(body).getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);try(OutputStream o=x.getResponseBody()){o.write(bytes);}}
  private static void binary(HttpExchange x,int status,byte[] bytes,String type,String disposition)throws IOException{x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("Content-Disposition",disposition);x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(status,bytes.length);try(OutputStream o=x.getResponseBody()){o.write(bytes);}}
  private static Map<String,Object> error(String c,String m,String r){return map("code",c,"message",m,"requestId",r);}
  private static Map<String,Object> map(Object...v){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put(String.valueOf(v[i]),v[i+1]);return m;}
  private static String env(String k,String d){String v=System.getenv(k);return v==null||v.trim().isEmpty()?d:v.trim();}
  private static String bootstrapPassword() throws IOException {
    String file=System.getenv("IPAM_ADMIN_PASSWORD_FILE");
    if(file==null||file.trim().isEmpty()) return System.getenv("IPAM_ADMIN_PASSWORD");
    Path path=Paths.get(file).toAbsolutePath().normalize();
    try { return Files.readString(path,StandardCharsets.UTF_8).trim(); }
    finally { Files.deleteIfExists(path); }
  }
  private static String random(){byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
  private static String hash(String s){try{byte[] h=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:h)b.append(String.format("%02x",x));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
  private static boolean constantEquals(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
  private static String cookie(HttpExchange x,String name){String c=x.getRequestHeaders().getFirst("Cookie");if(c==null)return null;for(String p:c.split(";")){String[] q=p.trim().split("=",2);if(q.length==2&&name.equals(q[0]))return q[1];}return null;}
  private static Upload upload(HttpExchange x)throws IOException{String content=x.getRequestHeaders().getFirst("Content-Type");if(content==null||!content.startsWith("multipart/form-data;"))throw new ApiError(400,"UPLOAD_CONTENT_TYPE","上传必须使用 multipart/form-data。");String marker="boundary=";int i=content.indexOf(marker);if(i<0)throw new ApiError(400,"UPLOAD_BOUNDARY","上传边界缺失。");String boundary="--"+content.substring(i+marker.length()).replace("\"","");byte[] data=readLimited(x.getRequestBody(),PhaseTwoService.MAX_BYTES+16384);byte[] sep=("\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);int head=index(data,sep,0);if(head<0)throw new ApiError(400,"UPLOAD_FORMAT","上传格式不正确。");String headers=new String(data,0,head,StandardCharsets.ISO_8859_1);int fn=headers.indexOf("filename=\"");if(fn<0)throw new ApiError(400,"UPLOAD_FILE_REQUIRED","请选择上传文件。");int endName=headers.indexOf('"',fn+10);if(endName<0)throw new ApiError(400,"UPLOAD_FORMAT","文件名格式不正确。");String name=headers.substring(fn+10,endName);byte[] tail=("\r\n"+boundary).getBytes(StandardCharsets.ISO_8859_1);int end=index(data,tail,head+sep.length);if(end<0)throw new ApiError(400,"UPLOAD_FORMAT","上传边界不完整。");return new Upload(name,Arrays.copyOfRange(data,head+sep.length,end));}
  private static byte[] readLimited(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))>=0){total+=n;if(total>limit)throw new ApiError(400,"UPLOAD_TOO_LARGE","上传文件超过 5 MiB 限制。");out.write(b,0,n);}return out.toByteArray();}private static int index(byte[] a,byte[] b,int from){outer:for(int i=from;i<=a.length-b.length;i++){for(int j=0;j<b.length;j++)if(a[i+j]!=b[j])continue outer;return i;}return-1;}
  private void setCookie(HttpExchange x,String token){x.getResponseHeaders().add("Set-Cookie","IPAM_SESSION="+token+"; Path=/; HttpOnly; SameSite=Lax; Max-Age="+(SESSION_HOURS*3600)+(secureCookie?"; Secure":""));}
  private void expireCookie(HttpExchange x){x.getResponseHeaders().add("Set-Cookie","IPAM_SESSION=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"+(secureCookie?"; Secure":""));}

  private static final class ApiError extends RuntimeException {final int status;final String code;ApiError(int status,String code,String message){super(message);this.status=status;this.code=code;}}
  private static final class Session {final String token,csrf;Session(String token,String csrf){this.token=token;this.csrf=csrf;}}
  private static final class Upload {final String fileName;final byte[] bytes;Upload(String n,byte[] b){fileName=n;bytes=b;}}
  private static final class Principal {long id,sessionId;String username,realName,role,passwordHash,csrfHash;boolean mustChangePassword;Map<String,Object> publicView(){return map("id",id,"username",username,"realName",realName,"role",role,"mustChangePassword",mustChangePassword);}}
  private static final class Cidr { final long start,end; Cidr(long s,long e){start=s;end=e;} static Cidr parse(String text){try{String[] a=text.split("/");if(a.length!=2)throw new Exception();long ip=ipv4(a[0]);int n=Integer.parseInt(a[1]);if(n<0||n>30)throw new Exception();long mask=n==0?0:(0xffffffffL << (32-n))&0xffffffffL;if((ip&mask)!=ip)throw new Exception();return new Cidr(ip,ip|(~mask&0xffffffffL));}catch(Exception e){throw new ApiError(400,"CIDR_INVALID","仅支持规范 IPv4 CIDR（/0–/30）。");}} static long ipv4(String s){String[] a=s.split("\\.");if(a.length!=4)throw new IllegalArgumentException();long r=0;for(String x:a){int n=Integer.parseInt(x);if(n<0||n>255)throw new IllegalArgumentException();r=(r<<8)|n;}return r;} boolean contains(String ip){long x=ipv4(ip);return x>start&&x<end;} }

  private static final class Database {
    private final Path file; Database(Path file){this.file=file;}
    Connection open() throws SQLException {Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA synchronous=FULL");}return c;}
    void migrate() throws Exception {try(Connection c=open()){try(Statement s=c.createStatement()){s.execute("PRAGMA journal_mode=WAL");}boolean legacy=hasHistory(c);try(Statement s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS schema_history (version TEXT PRIMARY KEY, description TEXT NOT NULL, checksum TEXT NOT NULL, installed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))) ");} apply(c,"001","stage1 foundation","/db/migration/V001__stage1_foundation.sql"); apply(c,"002","remove backup role and tech scope","/db/migration/V002__remove_backup_role_and_tech_scope.sql"); if(legacy&&!hasVersion(c,"003")){try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM sys_user WHERE role='ADMIN'")){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("V003 upgrade requires exactly one existing ADMIN; stop and repair the database manually.");}} apply(c,"003","initial admin protection","/db/migration/V003__initial_admin_protection.sql"); apply(c,"004","bulk import and application workflow","/db/migration/V004__bulk_import_and_application_workflow.sql"); apply(c,"005","outlet range and default pool","/db/migration/V005__outlet_range_and_default_pool.sql"); apply(c,"006","organization soft delete and network defaults","/db/migration/V006__organization_soft_delete_and_network_defaults.sql"); apply(c,"007","immediate release notification statistics export","/db/migration/V007__immediate_release_notification_statistics_export.sql"); apply(c,"008","outlet directory information","/db/migration/V008__outlet_directory_information.sql"); apply(c,"009","remove redundant managed outlet number","/db/migration/V009__remove_redundant_managed_outlet_no.sql"); apply(c,"010","six network planning and asset mapping","/db/migration/V010__six_network_planning_and_asset_mapping.sql"); apply(c,"011","outlet plan batch import","/db/migration/V011__outlet_plan_batch_import.sql"); apply(c,"012","dynamic segment prefix and remove override reason","/db/migration/V012__dynamic_segment_prefix_and_remove_override_reason.sql"); applyIpApplicationRebuild(c,"013","allow technology PC applicant identity","/db/migration/V013__allow_tech_pc_identity.sql"); applyIpApplicationRebuild(c,"014","preserve legacy PC identity without inference","/db/migration/V014__preserve_legacy_pc_identity.sql"); applySoftDeleteReuseRebuild(c,"015","allow soft-deleted outlet and network reuse","/db/migration/V015__allow_soft_deleted_network_reuse.sql"); applySoftDeleteReuseRebuild(c,"016","allow soft-deleted branch and user reuse","/db/migration/V016__allow_soft_deleted_branch_and_user_reuse.sql"); applySoftDeleteReuseRebuild(c,"017","reset demo business data and simplify assets","/db/migration/V017__reset_demo_business_data_and_simplify_assets.sql"); apply(c,"018","restore override reason for cross-range approval","/db/migration/V018__restore_override_reason_for_cross_range_approval.sql"); apply(c,"019","password policy and switch port","/db/migration/V019__password_policy_and_switch_port.sql");}}
    private boolean hasHistory(Connection c)throws SQLException {try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_history'")){return r.next();}}
    private boolean hasVersion(Connection c,String v)throws SQLException {try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM schema_history WHERE version=?")){p.setString(1,v);return p.executeQuery().next();}}
    void apply(Connection c,String v,String d,String resource) throws Exception {
      String sql=readResource(resource), sum=hash(sql);
      try(PreparedStatement p=c.prepareStatement("SELECT checksum FROM schema_history WHERE version=?")) { p.setString(1,v); ResultSet rs=p.executeQuery(); if(rs.next()){ if(!sum.equals(rs.getString(1))) throw new IllegalStateException("已发布迁移脚本校验和不匹配: V"+v); return; } }
      c.setAutoCommit(false);
      try {
        try(Statement s=c.createStatement()){ for(String part:sql.split(";\\s*(?:\\r?\\n|$)")){ if(!part.trim().isEmpty())s.executeUpdate(part); } }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO schema_history(version,description,checksum) VALUES(?,?,?)")){p.setString(1,v);p.setString(2,d);p.setString(3,sum);p.executeUpdate();}
        c.commit();
      } catch(Exception e) { c.rollback(); throw e; } finally { c.setAutoCommit(true); }
    }
    /* SQLite cannot drop and rebuild a parent table with foreign keys enabled.  V013
       is deliberately run as a single isolated migration: foreign keys are disabled
       before the transaction, restored afterwards, and foreign_key_check is a hard
       startup gate.  This preserves all application IDs referenced by assignments,
       notifications and release events while replacing the obsolete V004 CHECK. */
    void applyIpApplicationRebuild(Connection c,String v,String d,String resource) throws Exception {
      String sql=readResource(resource),sum=hash(sql);
      try(PreparedStatement p=c.prepareStatement("SELECT checksum FROM schema_history WHERE version=?")){p.setString(1,v);try(ResultSet rs=p.executeQuery()){if(rs.next()){String installed=rs.getString(1);if(!sum.equals(installed)&&!("013".equals(v)&&"b54358d0baecd20c8cd9811c0efe58fc8d4e8dddd73515ca0ecce90b7be55415".equals(installed)))throw new IllegalStateException("已发布迁移脚本校验和不匹配: V"+v);return;}}}
      if(!c.getAutoCommit())throw new IllegalStateException("V013 requires an idle migration connection.");
      try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=OFF");}
      boolean committed=false;
      try {
        c.setAutoCommit(false);
        try(Statement s=c.createStatement()){for(String part:sql.split(";\\s*(?:\\r?\\n|$)")){if(!part.trim().isEmpty())s.executeUpdate(part);}}
        try(PreparedStatement p=c.prepareStatement("INSERT INTO schema_history(version,description,checksum) VALUES(?,?,?)")){p.setString(1,v);p.setString(2,d);p.setString(3,sum);p.executeUpdate();}
        c.commit();committed=true;
      } catch(Exception e) {c.rollback();throw e;} finally {if(!c.getAutoCommit())c.setAutoCommit(true);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}}
      if(committed)try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("PRAGMA foreign_key_check")){if(rs.next())throw new IllegalStateException("V013 foreign-key validation failed: "+rs.getString(1)+" row "+rs.getLong(2));}
    }
    /* Soft-delete reuse rebuilds preserve history while releasing deleted business
       keys. Rebuilding parent tables needs the same isolated, checked protocol as V013. */
    void applySoftDeleteReuseRebuild(Connection c,String v,String d,String resource) throws Exception {
      String sql=readResource(resource),sum=hash(sql);
      try(PreparedStatement p=c.prepareStatement("SELECT checksum FROM schema_history WHERE version=?")){p.setString(1,v);try(ResultSet rs=p.executeQuery()){if(rs.next()){if(!sum.equals(rs.getString(1)))throw new IllegalStateException("已发布迁移脚本校验和不匹配: V"+v);return;}}}
      if(!c.getAutoCommit())throw new IllegalStateException("V"+v+" requires an idle migration connection.");
      try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=OFF");}
      boolean committed=false;
      try { c.setAutoCommit(false); try(Statement s=c.createStatement()){for(String part:sql.split(";\\s*(?:\\r?\\n|$)")){if(!part.trim().isEmpty())s.executeUpdate(part);}} try(PreparedStatement p=c.prepareStatement("INSERT INTO schema_history(version,description,checksum) VALUES(?,?,?)")){p.setString(1,v);p.setString(2,d);p.setString(3,sum);p.executeUpdate();} c.commit();committed=true;
      } catch(Exception e) {c.rollback();throw e;} finally {if(!c.getAutoCommit())c.setAutoCommit(true);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}}
      if(committed)try(Statement s=c.createStatement();ResultSet rs=s.executeQuery("PRAGMA foreign_key_check")){if(rs.next())throw new IllegalStateException("V"+v+" foreign-key validation failed: "+rs.getString(1)+" row "+rs.getLong(2));}
    }
    void bootstrapAdmin(String username,String password) throws Exception {try(Connection c=open();Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM sys_user WHERE role='ADMIN'")){if(rs.next()&&rs.getInt(1)>0)return;}if(username==null||password==null)throw new IllegalStateException("尚无管理员。请仅在首次启动时设置 IPAM_ADMIN_USERNAME 和 IPAM_ADMIN_PASSWORD。");if(username.trim().length()<3||username.length()>64)throw new IllegalStateException("初始管理员用户名长度必须为 3–64。");validatePassword(password);try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO sys_user(username,password_hash,real_name,role,is_initial_admin,must_change_password,password_changed_at) VALUES(?,?,?,?,1,1,?)")){p.setString(1,username.trim());p.setString(2,BCrypt.hashpw(password,BCrypt.gensalt(12)));p.setString(3,"初始管理员");p.setString(4,"ADMIN");p.setString(5,Instant.now().toString());p.executeUpdate();}audit(null,null,"BOOTSTRAP_ADMIN","USER",username,"SUCCESS",null);}
    Login login(String username,String password) throws Exception {try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT * FROM sys_user WHERE username=?")){p.setString(1,username);ResultSet rs=p.executeQuery();if(!rs.next())return null;Principal u=user(rs);Instant now=Instant.now();if(!uActive(c,u.id)||locked(c,u.id,now)||!BCrypt.checkpw(password,u.passwordHash)){failLogin(c,u.id,now);return null;}boolean weak=!PasswordPolicy.isCompliant(password);try(PreparedStatement up=c.prepareStatement("UPDATE sys_user SET failed_login_count=0,locked_until=NULL,last_login_at=?,updated_at=?,must_change_password=CASE WHEN ?=1 THEN 1 ELSE must_change_password END WHERE id=?")){up.setString(1,now.toString());up.setString(2,now.toString());up.setInt(3,weak?1:0);up.setLong(4,u.id);up.executeUpdate();}u.mustChangePassword=u.mustChangePassword||weak;return new Login(u);}}
    private boolean uActive(Connection c,long id)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT is_active FROM sys_user WHERE id=?")){p.setLong(1,id);ResultSet r=p.executeQuery();return r.next()&&r.getInt(1)==1;}}
    private boolean locked(Connection c,long id,Instant now)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT locked_until FROM sys_user WHERE id=?")){p.setLong(1,id);ResultSet r=p.executeQuery();return r.next()&&r.getString(1)!=null&&Instant.parse(r.getString(1)).isAfter(now);}}
    private void failLogin(Connection c,long id,Instant now)throws SQLException{try(PreparedStatement p=c.prepareStatement("UPDATE sys_user SET failed_login_count=failed_login_count+1,locked_until=CASE WHEN failed_login_count+1>=5 THEN ? ELSE locked_until END WHERE id=?")){p.setString(1,now.plus(Duration.ofMinutes(15)).toString());p.setLong(2,id);p.executeUpdate();}}
    Session createSession(long id)throws Exception{String t=random(),csrf=random(),now=Instant.now().toString(),exp=Instant.now().plus(Duration.ofHours(SESSION_HOURS)).toString();try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO sys_session(token_hash,csrf_token_hash,user_id,expires_at,last_seen_at) VALUES(?,?,?,?,?)")){p.setString(1,hash(t));p.setString(2,hash(csrf));p.setLong(3,id);p.setString(4,exp);p.setString(5,now);p.executeUpdate();}return new Session(t,csrf);}
    Principal session(String token)throws Exception{try(Connection c=open()){Principal u;String lastSeen;Instant now=Instant.now();try(PreparedStatement p=c.prepareStatement("SELECT s.id AS session_id,s.csrf_token_hash,s.last_seen_at,u.* FROM sys_session s JOIN sys_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>? AND u.is_active=1 AND u.deleted_at IS NULL")){p.setString(1,hash(token));p.setString(2,now.toString());try(ResultSet r=p.executeQuery()){if(!r.next())return null;u=user(r);u.sessionId=r.getLong("session_id");u.csrfHash=r.getString("csrf_token_hash");lastSeen=r.getString("last_seen_at");}}if(lastSeen==null||Instant.parse(lastSeen).isBefore(now.minus(Duration.ofMinutes(5))))try(PreparedStatement up=c.prepareStatement("UPDATE sys_session SET last_seen_at=? WHERE id=? AND (last_seen_at IS NULL OR last_seen_at<?)")){up.setString(1,now.toString());up.setLong(2,u.sessionId);up.setString(3,now.minus(Duration.ofMinutes(5)).toString());up.executeUpdate();}return u;}}
    void revokeSession(long id)throws Exception{change("UPDATE sys_session SET revoked_at=? WHERE id=?",Instant.now().toString(),id);}
    Session changePassword(long id,String password)throws Exception{try(Connection c=open()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement("UPDATE sys_user SET password_hash=?,must_change_password=0,password_changed_at=?,updated_at=? WHERE id=?")){String now=Instant.now().toString();p.setString(1,BCrypt.hashpw(password,BCrypt.gensalt(12)));p.setString(2,now);p.setString(3,now);p.setLong(4,id);if(p.executeUpdate()!=1)throw new ApiError(404,"USER_NOT_FOUND","账号不存在。");}try(PreparedStatement p=c.prepareStatement("UPDATE sys_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL")){p.setString(1,Instant.now().toString());p.setLong(2,id);p.executeUpdate();}c.commit();}return createSession(id);}
    long createUser(String username,String name,String password,String role,long creator)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO sys_user(username,password_hash,real_name,role,created_by,must_change_password,password_changed_at,is_initial_admin) VALUES(?,?,?,?,?,1,?,0)",Statement.RETURN_GENERATED_KEYS)){p.setString(1,username);p.setString(2,BCrypt.hashpw(password,BCrypt.gensalt(12)));p.setString(3,name);p.setString(4,role);p.setLong(5,creator);p.setString(6,Instant.now().toString());p.executeUpdate();ResultSet k=p.getGeneratedKeys();k.next();return k.getLong(1);}}
    void resetPassword(long id,String password)throws Exception{try(Connection c=open()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement("UPDATE sys_user SET password_hash=?,must_change_password=1,password_changed_at=?,updated_at=? WHERE id=? AND is_initial_admin=0 AND deleted_at IS NULL")){p.setString(1,BCrypt.hashpw(password,BCrypt.gensalt(12)));p.setString(2,Instant.now().toString());p.setString(3,Instant.now().toString());p.setLong(4,id);if(p.executeUpdate()!=1)throw new ApiError(404,"USER_NOT_FOUND","非初始管理员账号不存在或不可重置。");}revokeUserSessions(c,id);c.commit();}}
    void setUserActive(long id,boolean active)throws Exception{try(Connection c=open()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement("UPDATE sys_user SET is_active=?,updated_at=? WHERE id=? AND is_initial_admin=0 AND deleted_at IS NULL")){p.setInt(1,active?1:0);p.setString(2,Instant.now().toString());p.setLong(3,id);if(p.executeUpdate()!=1)throw new ApiError(400,"USER_STATE_PROTECTED","初始管理员、已删除或不存在的账号不能变更。");}if(!active)revokeUserSessions(c,id);c.commit();}}
    void deleteUser(long id)throws Exception{try(Connection c=open()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement("UPDATE sys_user SET is_active=0,deleted_at=?,updated_at=? WHERE id=? AND is_initial_admin=0 AND deleted_at IS NULL")){String now=Instant.now().toString();p.setString(1,now);p.setString(2,now);p.setLong(3,id);if(p.executeUpdate()!=1)throw new ApiError(400,"USER_DELETE_PROTECTED","初始管理员、已删除或不存在的账号不能删除。");}revokeUserSessions(c,id);c.commit();}}
    void requireActiveBranch(long id)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT 1 FROM branch WHERE id=? AND is_active=1 AND deleted_at IS NULL")){p.setLong(1,id);if(!p.executeQuery().next())throw new ApiError(400,"BRANCH_INVALID","请选择已启用的支行。");}}
    boolean visibleBranchCodeExists(String code)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT 1 FROM branch WHERE code=? AND deleted_at IS NULL")){p.setString(1,code);return p.executeQuery().next();}}
    boolean visibleOutletCodeExists(long branchId,String code)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT 1 FROM outlet WHERE branch_id=? AND code=? AND deleted_at IS NULL")){p.setLong(1,branchId);p.setString(2,code);return p.executeQuery().next();}}
    boolean visibleUsernameExists(String username)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT 1 FROM sys_user WHERE username=? AND deleted_at IS NULL")){p.setString(1,username);return p.executeQuery().next();}}
    private void revokeUserSessions(Connection c,long id)throws SQLException{try(PreparedStatement p=c.prepareStatement("UPDATE sys_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL")){p.setString(1,Instant.now().toString());p.setLong(2,id);p.executeUpdate();}}
    void assertIpInRange(long range,String ip)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT cidr FROM ip_range WHERE id=? AND is_active=1")){p.setLong(1,range);ResultSet r=p.executeQuery();if(!r.next()||!Cidr.parse(r.getString(1)).contains(ip))throw new ApiError(400,"IP_RANGE_INVALID","IP 必须属于启用地址段，且不能是网络地址或广播地址。");}}
    long insert(String sql,Object...values)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(p,values);p.executeUpdate();ResultSet k=p.getGeneratedKeys();k.next();return k.getLong(1);}}
    void change(String sql,Object...values)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement(sql)){bind(p,values);if(p.executeUpdate()!=1)throw new ApiError(404,"RESOURCE_NOT_FOUND","记录不存在。");}}
    List<Map<String,Object>> rows(String sql)throws Exception{try(Connection c=open();Statement p=c.createStatement();ResultSet r=p.executeQuery(sql)){List<Map<String,Object>> out=new ArrayList<>();ResultSetMetaData md=r.getMetaData();while(r.next()){Map<String,Object> m=new LinkedHashMap<>();for(int i=1;i<=md.getColumnCount();i++)m.put(md.getColumnLabel(i),r.getObject(i));out.add(m);}return out;}}
    List<Map<String,Object>> users()throws Exception{return rows("SELECT u.id,u.username,u.real_name AS realName,u.role,u.is_active AS active,u.is_initial_admin AS initialAdmin,u.deleted_at AS deletedAt,u.must_change_password AS mustChangePassword,u.locked_until AS lockedUntil,u.created_at AS createdAt,creator.username AS creator FROM sys_user u LEFT JOIN sys_user creator ON creator.id=u.created_by WHERE u.role IN ('ADMIN','TECH_ADMIN') ORDER BY u.deleted_at IS NOT NULL,u.role,u.username");}
    List<Map<String,Object>> auditRows()throws Exception{
      List<Map<String,Object>> out=rows("SELECT a.id,a.action,a.target_type AS targetType,a.target_id AS targetId,a.result,a.created_at AS createdAt,u.username AS actor,a.detail_json AS detailJson FROM audit_log a LEFT JOIN sys_user u ON u.id=a.actor_user_id ORDER BY a.id DESC LIMIT 200");
      for(Map<String,Object> row:out){
        if("UPDATE_SWITCH_PORT".equals(row.get("action"))){
          Map<String,Object> detail=safeAuditDetail(row.get("detailJson"));
          row.put("switchPortBefore",safeAuditPort(detail.get("before")));
          row.put("switchPortAfter",safeAuditPort(detail.get("after")));
        }
        row.remove("detailJson");
      }
      return out;
    }
    private static Map<String,Object> safeAuditDetail(Object raw){
      Map<String,Object> result=new LinkedHashMap<>();
      try{
        JsonElement parsed=JsonParser.parseString(raw==null?"":String.valueOf(raw));
        if(parsed.isJsonObject()){
          JsonObject obj=parsed.getAsJsonObject();
          if(obj.has("before")&&obj.get("before").isJsonPrimitive()&&obj.get("before").getAsJsonPrimitive().isString())result.put("before",obj.get("before").getAsString());
          if(obj.has("after")&&obj.get("after").isJsonPrimitive()&&obj.get("after").getAsJsonPrimitive().isString())result.put("after",obj.get("after").getAsString());
        }
      }catch(Exception ignored){}
      return result;
    }
    private static String safeAuditPort(Object value){if(value==null)return "—";String text=String.valueOf(value);return text.trim().isEmpty()?"—":text;}
    void audit(Long actor,String req,String action,String type,String target,String result,String detail)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO audit_log(request_id,actor_user_id,action,target_type,target_id,result,detail_json) VALUES(?,?,?,?,?,?,?)")){p.setString(1,req);if(actor==null)p.setNull(2,Types.INTEGER);else p.setLong(2,actor);p.setString(3,action);p.setString(4,type);p.setString(5,target);p.setString(6,result);p.setString(7,detail);p.executeUpdate();}}
    private static Principal user(ResultSet r)throws SQLException{Principal p=new Principal();p.id=r.getLong("id");p.username=r.getString("username");p.realName=r.getString("real_name");p.role=r.getString("role");p.passwordHash=r.getString("password_hash");p.mustChangePassword=r.getInt("must_change_password")==1;return p;}
    private static void bind(PreparedStatement p,Object...v)throws SQLException{for(int i=0;i<v.length;i++){Object x=v[i];if(x==null)p.setNull(i+1,Types.VARCHAR);else if(x instanceof Long)p.setLong(i+1,(Long)x);else if(x instanceof Integer)p.setInt(i+1,(Integer)x);else p.setString(i+1,String.valueOf(x));}}
    private static String readResource(String resource)throws IOException{try(InputStream in=IpamApplication.class.getResourceAsStream(resource)){if(in==null)throw new FileNotFoundException(resource);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    static final class Login {final Principal user;Login(Principal u){user=u;}}
  }
}
