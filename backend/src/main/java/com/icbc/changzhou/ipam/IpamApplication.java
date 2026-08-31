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
  private final Path staticDir;
  private final boolean secureCookie;

  private IpamApplication(Database db, Path staticDir, boolean secureCookie) { this.db = db; this.staticDir = staticDir; this.secureCookie = secureCookie; }

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
    System.out.println("IPAM stage-1 is listening on http://" + env("IPAM_BIND", "127.0.0.1") + ":" + port + ", database=" + data.resolve("ipam.db"));
  }

  private void handle(HttpExchange x) throws IOException {
    String requestId = UUID.randomUUID().toString();
    try {
      String path = x.getRequestURI().getPath();
      if (path.startsWith("/api/v1/")) api(x, requestId, path.substring(8)); else serveStatic(x, path);
    } catch (ApiError e) { respond(x, e.status, error(e.code, e.getMessage(), requestId)); }
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
    if (p.mustChangePassword) throw new ApiError(403,"PASSWORD_CHANGE_REQUIRED","该账号必须先修改初始或重置后的密码。");
    if ("branches".equals(path) && "GET".equals(method)) { respond(x,200,map("items",db.rows("SELECT id,code,name,is_active AS active FROM branch ORDER BY name"))); return; }
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
    Map<String,Object> out=p.publicView(); out.put("csrfToken",replacement.csrf); out.put("message","密码已修改，其他登录会话已失效。"); respond(x,200,out);
  }

  private void admin(HttpExchange x, Principal p, String requestId, String path) throws Exception {
    String m=x.getRequestMethod();
    if ("users".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.users())); return; }
    if ("users".equals(path) && "POST".equals(m)) { createUser(x,p,requestId); return; }
    if (path.matches("users/\\d+/reset-password") && "POST".equals(m)) { resetPassword(x,p,requestId,parseId(path)); return; }
    if (path.matches("users/\\d+/active") && "PUT".equals(m)) { activeUser(x,p,requestId,parseId(path)); return; }
    if ("branches".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.rows("SELECT id,code,name,is_active AS active FROM branch ORDER BY name"))); return; }
    if ("branches".equals(path) && "POST".equals(m)) { createBranch(x,p,requestId); return; }
    if (path.matches("branches/\\d+") && "PUT".equals(m)) { updateBranch(x,p,requestId,parseId(path)); return; }
    if ("outlets".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.rows("SELECT o.id,o.branch_id AS branchId,b.name AS branchName,o.code,o.name,o.is_active AS active FROM outlet o JOIN branch b ON b.id=o.branch_id ORDER BY b.name,o.name"))); return; }
    if ("outlets".equals(path) && "POST".equals(m)) { createOutlet(x,p,requestId); return; }
    if (path.matches("outlets/\\d+") && "PUT".equals(m)) { updateOutlet(x,p,requestId,parseId(path)); return; }
    if ("ip-ranges".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.rows("SELECT r.id,r.branch_id AS branchId,b.name AS branchName,r.cidr,r.subnet_mask AS subnetMask,r.gateway,r.dns_primary AS dnsPrimary,r.dns_secondary AS dnsSecondary,r.is_active AS active FROM ip_range r JOIN branch b ON b.id=r.branch_id ORDER BY b.name,r.cidr"))); return; }
    if ("ip-ranges".equals(path) && "POST".equals(m)) { createRange(x,p,requestId); return; }
    if (path.matches("ip-ranges/\\d+") && "PUT".equals(m)) { updateRange(x,p,requestId,parseId(path)); return; }
    if ("ip-pool".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.rows("SELECT p.id,p.ip_range_id AS rangeId,r.cidr,p.ip_address AS ipAddress,p.is_reserved AS reserved,p.reservation_reason AS reservationReason,p.is_active AS active FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id ORDER BY r.cidr,p.ip_address"))); return; }
    if ("ip-pool".equals(path) && "POST".equals(m)) { createPool(x,p,requestId); return; }
    if (path.matches("ip-pool/\\d+") && "PUT".equals(m)) { updatePool(x,p,requestId,parseId(path)); return; }
    if ("audit".equals(path) && "GET".equals(m)) { respond(x,200,map("items",db.rows("SELECT a.id,a.action,a.target_type AS targetType,a.target_id AS targetId,a.result,a.created_at AS createdAt,u.username AS actor FROM audit_log a LEFT JOIN sys_user u ON u.id=a.actor_user_id ORDER BY a.id DESC LIMIT 200"))); return; }
    throw new ApiError(404,"NOT_FOUND","管理接口不存在。");
  }

  private void createUser(HttpExchange x, Principal p,String r) throws Exception { JsonObject b=body(x); String requested=optional(b,"role",32); if(requested!=null&&!"TECH_ADMIN".equals(requested)) throw new ApiError(400,"ROLE_INVALID","当前版本仅支持创建科技管理员账号。"); String password=required(b,"initialPassword",128); validatePassword(password); long id=db.createUser(required(b,"username",64),required(b,"realName",100),password,p.id); audit(p,r,"CREATE_USER","USER",String.valueOf(id),"SUCCESS",map("role","TECH_ADMIN")); respond(x,201,map("id",id,"mustChangePassword",false)); }
  private void resetPassword(HttpExchange x,Principal p,String r,long id) throws Exception { String password=required(body(x),"newPassword",128); validatePassword(password); db.resetPassword(id,password); audit(p,r,"RESET_PASSWORD","USER",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }
  private void activeUser(HttpExchange x,Principal p,String r,long id) throws Exception { boolean active=bool(body(x),"active"); if(id==p.id&&!active) throw new ApiError(400,"SELF_DISABLE","不能禁用当前管理员账号。"); db.setUserActive(id,active); audit(p,r,active?"ENABLE_USER":"DISABLE_USER","USER",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }
  private void createBranch(HttpExchange x,Principal p,String r) throws Exception { JsonObject b=body(x); long id=db.insert("INSERT INTO branch(code,name) VALUES(?,?)",required(b,"code",32),required(b,"name",100)); audit(p,r,"CREATE_BRANCH","BRANCH",String.valueOf(id),"SUCCESS",null); respond(x,201,map("id",id)); }
  private void updateBranch(HttpExchange x,Principal p,String r,long id) throws Exception { JsonObject b=body(x); db.change("UPDATE branch SET code=?,name=?,is_active=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",required(b,"code",32),required(b,"name",100),bool(b,"active")?1:0,id); audit(p,r,"UPDATE_BRANCH","BRANCH",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }
  private void createOutlet(HttpExchange x,Principal p,String r) throws Exception { JsonObject b=body(x); long branchId=number(b,"branchId"); db.requireActiveBranch(branchId); long id=db.insert("INSERT INTO outlet(branch_id,code,name) VALUES(?,?,?)",branchId,required(b,"code",32),required(b,"name",100)); audit(p,r,"CREATE_OUTLET","OUTLET",String.valueOf(id),"SUCCESS",null); respond(x,201,map("id",id)); }
  private void updateOutlet(HttpExchange x,Principal p,String r,long id) throws Exception { JsonObject b=body(x); db.change("UPDATE outlet SET branch_id=?,code=?,name=?,is_active=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",number(b,"branchId"),required(b,"code",32),required(b,"name",100),bool(b,"active")?1:0,id); audit(p,r,"UPDATE_OUTLET","OUTLET",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }
  private void createRange(HttpExchange x,Principal p,String r) throws Exception { JsonObject b=body(x); String cidr=required(b,"cidr",18); Cidr.parse(cidr); long branchId=number(b,"branchId"); db.requireActiveBranch(branchId); long id=db.insert("INSERT INTO ip_range(branch_id,cidr,subnet_mask,gateway,dns_primary,dns_secondary) VALUES(?,?,?,?,?,?)",branchId,cidr,required(b,"subnetMask",45),required(b,"gateway",45),required(b,"dnsPrimary",45),optional(b,"dnsSecondary",45)); audit(p,r,"CREATE_IP_RANGE","IP_RANGE",String.valueOf(id),"SUCCESS",null); respond(x,201,map("id",id)); }
  private void updateRange(HttpExchange x,Principal p,String r,long id) throws Exception { JsonObject b=body(x); String cidr=required(b,"cidr",18); Cidr.parse(cidr); db.change("UPDATE ip_range SET branch_id=?,cidr=?,subnet_mask=?,gateway=?,dns_primary=?,dns_secondary=?,is_active=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",number(b,"branchId"),cidr,required(b,"subnetMask",45),required(b,"gateway",45),required(b,"dnsPrimary",45),optional(b,"dnsSecondary",45),bool(b,"active")?1:0,id); audit(p,r,"UPDATE_IP_RANGE","IP_RANGE",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }
  private void createPool(HttpExchange x,Principal p,String r) throws Exception { JsonObject b=body(x); long range=number(b,"rangeId"); String ip=required(b,"ipAddress",45); db.assertIpInRange(range,ip); long id=db.insert("INSERT INTO ip_address_pool(ip_range_id,ip_address,is_reserved,reservation_reason) VALUES(?,?,?,?)",range,ip,bool(b,"reserved")?1:0,optional(b,"reservationReason",200)); audit(p,r,"CREATE_IP_POOL","IP_POOL",String.valueOf(id),"SUCCESS",null); respond(x,201,map("id",id)); }
  private void updatePool(HttpExchange x,Principal p,String r,long id) throws Exception { JsonObject b=body(x); long range=number(b,"rangeId"); String ip=required(b,"ipAddress",45); db.assertIpInRange(range,ip); db.change("UPDATE ip_address_pool SET ip_range_id=?,ip_address=?,is_reserved=?,reservation_reason=?,is_active=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",range,ip,bool(b,"reserved")?1:0,optional(b,"reservationReason",200),bool(b,"active")?1:0,id); audit(p,r,"UPDATE_IP_POOL","IP_POOL",String.valueOf(id),"SUCCESS",null); respond(x,204,null); }

  private Principal authenticate(HttpExchange x) throws Exception { String token=cookie(x,"IPAM_SESSION"); if(token==null) throw new ApiError(401,"AUTH_REQUIRED","请先登录。"); Principal p=db.session(token); if(p==null) throw new ApiError(401,"SESSION_INVALID","会话已失效，请重新登录。"); return p; }
  private void verifyCsrf(HttpExchange x,Principal p) { String csrf=x.getRequestHeaders().getFirst("X-CSRF-Token"); if(csrf==null||!constantEquals(hash(csrf),p.csrfHash)) throw new ApiError(403,"CSRF_INVALID","请求安全校验失败，请刷新页面后重试。"); String origin=x.getRequestHeaders().getFirst("Origin"); String referer=x.getRequestHeaders().getFirst("Referer"); String host=x.getRequestHeaders().getFirst("Host"); if(origin!=null&&!origin.isEmpty()&&!origin.endsWith("://"+host)) throw new ApiError(403,"ORIGIN_INVALID","跨站请求被拒绝。"); if(origin==null&&referer!=null&&!referer.startsWith("http://"+host)&&!referer.startsWith("https://"+host)) throw new ApiError(403,"ORIGIN_INVALID","跨站请求被拒绝。"); }
  private void requireAdmin(Principal p) { if(!"ADMIN".equals(p.role)) throw new ApiError(403,"FORBIDDEN","您没有管理端访问权限。"); }
  private void audit(Principal p,String requestId,String action,String type,String target,String result,Object detail) throws Exception { db.audit(p==null?null:p.id,requestId,action,type,target,result,detail==null?null:JSON.toJson(detail)); }
  private void serveStatic(HttpExchange x,String path) throws IOException { if(!"GET".equals(x.getRequestMethod())&& !"HEAD".equals(x.getRequestMethod())) { respond(x,405,map("code","METHOD_NOT_ALLOWED")); return; } Path p=staticDir.resolve(path.equals("/")?"index.html":path.substring(1)).normalize(); if(!p.startsWith(staticDir)||!Files.isRegularFile(p)) p=staticDir.resolve("index.html"); if(!Files.isRegularFile(p)){ respond(x,503,map("code","FRONTEND_NOT_BUILT","message","前端静态文件尚未构建。")); return;} String type=p.toString().endsWith(".js")?"application/javascript; charset=utf-8":p.toString().endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8"; byte[] bytes=Files.readAllBytes(p); x.getResponseHeaders().set("Content-Type",type); x.getResponseHeaders().set("X-Content-Type-Options","nosniff"); x.getResponseHeaders().set("Cache-Control","no-store"); x.sendResponseHeaders(200,"HEAD".equals(x.getRequestMethod())?-1:bytes.length); if(!"HEAD".equals(x.getRequestMethod())) try(OutputStream o=x.getResponseBody()){o.write(bytes);} }
  private static JsonObject body(HttpExchange x) throws IOException { try(Reader r=new InputStreamReader(x.getRequestBody(),StandardCharsets.UTF_8)){JsonElement e=JsonParser.parseReader(r);if(!e.isJsonObject())throw new ApiError(400,"INVALID_BODY","请求内容必须是 JSON 对象。");return e.getAsJsonObject();}catch(com.google.gson.JsonParseException e){throw new ApiError(400,"INVALID_JSON","请求 JSON 格式不正确。");} }
  private static String required(JsonObject b,String key,int max){String v=optional(b,key,max);if(v==null)throw new ApiError(400,"VALIDATION_ERROR",key+" 为必填项。");return v;}
  private static String optional(JsonObject b,String key,int max){if(!b.has(key)||b.get(key).isJsonNull())return null;String v=b.get(key).getAsString().trim();if(v.isEmpty()||v.length()>max)throw new ApiError(400,"VALIDATION_ERROR",key+" 格式不正确。");return v;}
  private static long number(JsonObject b,String key){try{return b.get(key).getAsLong();}catch(Exception e){throw new ApiError(400,"VALIDATION_ERROR",key+" 必须是有效编号。");}}
  private static boolean bool(JsonObject b,String key){try{return b.get(key).getAsBoolean();}catch(Exception e){throw new ApiError(400,"VALIDATION_ERROR",key+" 必须是布尔值。");}}
  private static long parseId(String path){String[] a=path.split("/");return Long.parseLong(a[1]);}
  private static void validatePassword(String v){if(v.length()<6||v.length()>72)throw new ApiError(400,"PASSWORD_POLICY","密码长度须为 6–72 位。");}
  private static void respond(HttpExchange x,int status,Object body) throws IOException {x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");if(status==204){x.sendResponseHeaders(204,-1);return;}byte[] bytes=JSON.toJson(body).getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);try(OutputStream o=x.getResponseBody()){o.write(bytes);}}
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
  private void setCookie(HttpExchange x,String token){x.getResponseHeaders().add("Set-Cookie","IPAM_SESSION="+token+"; Path=/; HttpOnly; SameSite=Lax; Max-Age="+(SESSION_HOURS*3600)+(secureCookie?"; Secure":""));}
  private void expireCookie(HttpExchange x){x.getResponseHeaders().add("Set-Cookie","IPAM_SESSION=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"+(secureCookie?"; Secure":""));}

  private static final class ApiError extends RuntimeException {final int status;final String code;ApiError(int status,String code,String message){super(message);this.status=status;this.code=code;}}
  private static final class Session {final String token,csrf;Session(String token,String csrf){this.token=token;this.csrf=csrf;}}
  private static final class Principal {long id,sessionId;String username,realName,role,passwordHash,csrfHash;boolean mustChangePassword;Map<String,Object> publicView(){return map("id",id,"username",username,"realName",realName,"role",role,"mustChangePassword",mustChangePassword);}}
  private static final class Cidr { final long start,end; Cidr(long s,long e){start=s;end=e;} static Cidr parse(String text){try{String[] a=text.split("/");if(a.length!=2)throw new Exception();long ip=ipv4(a[0]);int n=Integer.parseInt(a[1]);if(n<0||n>30)throw new Exception();long mask=n==0?0:(0xffffffffL << (32-n))&0xffffffffL;if((ip&mask)!=ip)throw new Exception();return new Cidr(ip,ip|(~mask&0xffffffffL));}catch(Exception e){throw new ApiError(400,"CIDR_INVALID","仅支持规范 IPv4 CIDR（/0–/30）。");}} static long ipv4(String s){String[] a=s.split("\\.");if(a.length!=4)throw new IllegalArgumentException();long r=0;for(String x:a){int n=Integer.parseInt(x);if(n<0||n>255)throw new IllegalArgumentException();r=(r<<8)|n;}return r;} boolean contains(String ip){long x=ipv4(ip);return x>start&&x<end;} }

  private static final class Database {
    private final Path file; Database(Path file){this.file=file;}
    Connection open() throws SQLException {Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");}return c;}
    void migrate() throws Exception {try(Connection c=open()){try(Statement s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS schema_history (version TEXT PRIMARY KEY, description TEXT NOT NULL, checksum TEXT NOT NULL, installed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))) ");} apply(c,"001","stage1 foundation","/db/migration/V001__stage1_foundation.sql"); apply(c,"002","remove backup role and tech scope","/db/migration/V002__remove_backup_role_and_tech_scope.sql");}}
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
    void bootstrapAdmin(String username,String password) throws Exception {try(Connection c=open();Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM sys_user WHERE role='ADMIN'")){if(rs.next()&&rs.getInt(1)>0)return;}if(username==null||password==null)throw new IllegalStateException("尚无管理员。请仅在首次启动时设置 IPAM_ADMIN_USERNAME 和 IPAM_ADMIN_PASSWORD。");if(username.trim().length()<3||username.length()>64)throw new IllegalStateException("初始管理员用户名长度必须为 3–64。");validatePassword(password);try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO sys_user(username,password_hash,real_name,role,must_change_password,password_changed_at) VALUES(?,?,?,?,1,?)")){p.setString(1,username.trim());p.setString(2,BCrypt.hashpw(password,BCrypt.gensalt(12)));p.setString(3,"初始管理员");p.setString(4,"ADMIN");p.setString(5,Instant.now().toString());p.executeUpdate();}audit(null,null,"BOOTSTRAP_ADMIN","USER",username,"SUCCESS",null);}
    Login login(String username,String password) throws Exception {try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT * FROM sys_user WHERE username=?")){p.setString(1,username);ResultSet rs=p.executeQuery();if(!rs.next())return null;Principal u=user(rs);Instant now=Instant.now();if(!uActive(c,u.id)||locked(c,u.id,now)||!BCrypt.checkpw(password,u.passwordHash)){failLogin(c,u.id,now);return null;}try(PreparedStatement up=c.prepareStatement("UPDATE sys_user SET failed_login_count=0,locked_until=NULL,last_login_at=?,updated_at=? WHERE id=?")){up.setString(1,now.toString());up.setString(2,now.toString());up.setLong(3,u.id);up.executeUpdate();}return new Login(u);}}
    private boolean uActive(Connection c,long id)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT is_active FROM sys_user WHERE id=?")){p.setLong(1,id);ResultSet r=p.executeQuery();return r.next()&&r.getInt(1)==1;}}
    private boolean locked(Connection c,long id,Instant now)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT locked_until FROM sys_user WHERE id=?")){p.setLong(1,id);ResultSet r=p.executeQuery();return r.next()&&r.getString(1)!=null&&Instant.parse(r.getString(1)).isAfter(now);}}
    private void failLogin(Connection c,long id,Instant now)throws SQLException{try(PreparedStatement p=c.prepareStatement("UPDATE sys_user SET failed_login_count=failed_login_count+1,locked_until=CASE WHEN failed_login_count+1>=5 THEN ? ELSE locked_until END WHERE id=?")){p.setString(1,now.plus(Duration.ofMinutes(15)).toString());p.setLong(2,id);p.executeUpdate();}}
    Session createSession(long id)throws Exception{String t=random(),csrf=random(),now=Instant.now().toString(),exp=Instant.now().plus(Duration.ofHours(SESSION_HOURS)).toString();try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO sys_session(token_hash,csrf_token_hash,user_id,expires_at,last_seen_at) VALUES(?,?,?,?,?)")){p.setString(1,hash(t));p.setString(2,hash(csrf));p.setLong(3,id);p.setString(4,exp);p.setString(5,now);p.executeUpdate();}return new Session(t,csrf);}
    Principal session(String token)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT s.id AS session_id,s.csrf_token_hash,u.* FROM sys_session s JOIN sys_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>? AND u.is_active=1")){p.setString(1,hash(token));p.setString(2,Instant.now().toString());ResultSet r=p.executeQuery();if(!r.next())return null;Principal u=user(r);u.sessionId=r.getLong("session_id");u.csrfHash=r.getString("csrf_token_hash");try(PreparedStatement up=c.prepareStatement("UPDATE sys_session SET last_seen_at=? WHERE id=?")){up.setString(1,Instant.now().toString());up.setLong(2,u.sessionId);up.executeUpdate();}return u;}}
    void revokeSession(long id)throws Exception{change("UPDATE sys_session SET revoked_at=? WHERE id=?",Instant.now().toString(),id);}
    Session changePassword(long id,String password)throws Exception{try(Connection c=open()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement("UPDATE sys_user SET password_hash=?,must_change_password=0,password_changed_at=?,updated_at=? WHERE id=?")){String now=Instant.now().toString();p.setString(1,BCrypt.hashpw(password,BCrypt.gensalt(12)));p.setString(2,now);p.setString(3,now);p.setLong(4,id);if(p.executeUpdate()!=1)throw new ApiError(404,"USER_NOT_FOUND","账号不存在。");}try(PreparedStatement p=c.prepareStatement("UPDATE sys_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL")){p.setString(1,Instant.now().toString());p.setLong(2,id);p.executeUpdate();}c.commit();}return createSession(id);}
    long createUser(String username,String name,String password,long creator)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO sys_user(username,password_hash,real_name,role,created_by,must_change_password,password_changed_at) VALUES(?,?,?,'TECH_ADMIN',?,0,?)",Statement.RETURN_GENERATED_KEYS)){p.setString(1,username);p.setString(2,BCrypt.hashpw(password,BCrypt.gensalt(12)));p.setString(3,name);p.setLong(4,creator);p.setString(5,Instant.now().toString());p.executeUpdate();ResultSet k=p.getGeneratedKeys();k.next();return k.getLong(1);}}
    void resetPassword(long id,String password)throws Exception{try(Connection c=open()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement("UPDATE sys_user SET password_hash=?,must_change_password=0,password_changed_at=?,updated_at=? WHERE id=? AND role = 'TECH_ADMIN'")){p.setString(1,BCrypt.hashpw(password,BCrypt.gensalt(12)));p.setString(2,Instant.now().toString());p.setString(3,Instant.now().toString());p.setLong(4,id);if(p.executeUpdate()!=1)throw new ApiError(404,"USER_NOT_FOUND","科技管理员账号不存在。");}revokeUserSessions(c,id);c.commit();}}
    void setUserActive(long id,boolean active)throws Exception{try(Connection c=open()){c.setAutoCommit(false);try(PreparedStatement p=c.prepareStatement("UPDATE sys_user SET is_active=?,updated_at=? WHERE id=?")){p.setInt(1,active?1:0);p.setString(2,Instant.now().toString());p.setLong(3,id);if(p.executeUpdate()!=1)throw new ApiError(404,"USER_NOT_FOUND","账号不存在。");}if(!active)revokeUserSessions(c,id);c.commit();}}
    void requireActiveBranch(long id)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT 1 FROM branch WHERE id=? AND is_active=1")){p.setLong(1,id);if(!p.executeQuery().next())throw new ApiError(400,"BRANCH_INVALID","请选择已启用的支行。");}}
    private void revokeUserSessions(Connection c,long id)throws SQLException{try(PreparedStatement p=c.prepareStatement("UPDATE sys_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL")){p.setString(1,Instant.now().toString());p.setLong(2,id);p.executeUpdate();}}
    void assertIpInRange(long range,String ip)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("SELECT cidr FROM ip_range WHERE id=? AND is_active=1")){p.setLong(1,range);ResultSet r=p.executeQuery();if(!r.next()||!Cidr.parse(r.getString(1)).contains(ip))throw new ApiError(400,"IP_RANGE_INVALID","IP 必须属于启用地址段，且不能是网络地址或广播地址。");}}
    long insert(String sql,Object...values)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(p,values);p.executeUpdate();ResultSet k=p.getGeneratedKeys();k.next();return k.getLong(1);}}
    void change(String sql,Object...values)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement(sql)){bind(p,values);if(p.executeUpdate()!=1)throw new ApiError(404,"RESOURCE_NOT_FOUND","记录不存在。");}}
    List<Map<String,Object>> rows(String sql)throws Exception{try(Connection c=open();Statement p=c.createStatement();ResultSet r=p.executeQuery(sql)){List<Map<String,Object>> out=new ArrayList<>();ResultSetMetaData md=r.getMetaData();while(r.next()){Map<String,Object> m=new LinkedHashMap<>();for(int i=1;i<=md.getColumnCount();i++)m.put(md.getColumnLabel(i),r.getObject(i));out.add(m);}return out;}}
    List<Map<String,Object>> users()throws Exception{return rows("SELECT u.id,u.username,u.real_name AS realName,u.role,u.is_active AS active,u.must_change_password AS mustChangePassword,u.locked_until AS lockedUntil FROM sys_user u WHERE u.role IN ('ADMIN','TECH_ADMIN') ORDER BY u.role,u.username");}
    void audit(Long actor,String req,String action,String type,String target,String result,String detail)throws Exception{try(Connection c=open();PreparedStatement p=c.prepareStatement("INSERT INTO audit_log(request_id,actor_user_id,action,target_type,target_id,result,detail_json) VALUES(?,?,?,?,?,?,?)")){p.setString(1,req);if(actor==null)p.setNull(2,Types.INTEGER);else p.setLong(2,actor);p.setString(3,action);p.setString(4,type);p.setString(5,target);p.setString(6,result);p.setString(7,detail);p.executeUpdate();}}
    private static Principal user(ResultSet r)throws SQLException{Principal p=new Principal();p.id=r.getLong("id");p.username=r.getString("username");p.realName=r.getString("real_name");p.role=r.getString("role");p.passwordHash=r.getString("password_hash");p.mustChangePassword=r.getInt("must_change_password")==1;return p;}
    private static void bind(PreparedStatement p,Object...v)throws SQLException{for(int i=0;i<v.length;i++){Object x=v[i];if(x==null)p.setNull(i+1,Types.VARCHAR);else if(x instanceof Long)p.setLong(i+1,(Long)x);else if(x instanceof Integer)p.setInt(i+1,(Integer)x);else p.setString(i+1,String.valueOf(x));}}
    private static String readResource(String resource)throws IOException{try(InputStream in=IpamApplication.class.getResourceAsStream(resource)){if(in==null)throw new FileNotFoundException(resource);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    static final class Login {final Principal user;Login(Principal u){user=u;}}
  }
}
