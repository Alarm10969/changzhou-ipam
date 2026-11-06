package com.icbc.changzhou.ipam;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Full database backups and a staged, recoverable database replacement. */
final class BackupService implements AutoCloseable {
  interface Migrator { void migrate(Path database) throws Exception; }
  static final long MAX_UPLOAD_BYTES = 256L * 1024 * 1024;
  private static final Gson JSON = new Gson();
  private static final String FORMAT = "changzhou-ipam-backup";
  private static final Duration PREVIEW_TTL = Duration.ofHours(1);
  private static final String[] MIGRATIONS = {
    "stage1_foundation", "remove_backup_role_and_tech_scope", "initial_admin_protection",
    "bulk_import_and_application_workflow", "outlet_range_and_default_pool",
    "organization_soft_delete_and_network_defaults", "immediate_release_notification_statistics_export",
    "outlet_directory_information", "remove_redundant_managed_outlet_no",
    "six_network_planning_and_asset_mapping", "outlet_plan_batch_import",
    "dynamic_segment_prefix_and_remove_override_reason", "allow_tech_pc_identity",
    "preserve_legacy_pc_identity", "allow_soft_deleted_network_reuse",
    "allow_soft_deleted_branch_and_user_reuse", "reset_demo_business_data_and_simplify_assets",
    "restore_override_reason_for_cross_range_approval", "password_policy_and_switch_port",
    "manual_assignment_and_gateway", "mac_address", "resource_maintenance", "assignment_batch_import", "outlet_directory_lines"
  };
  private final Path db, root, marker, metadata;
  private final MaintenanceGate gate;
  private final Migrator migrator;
  private final int targetSchema;
  private final Map<String,String> trustedStructure;
  private final ConcurrentMap<String,Preview> previews = new ConcurrentHashMap<>();
  private final AtomicBoolean restoreQueued = new AtomicBoolean();
  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "ipam-database-restore"); t.setDaemon(true); return t;
  });

  BackupService(Path dbFile, MaintenanceGate gate, Migrator migrator) throws Exception {
    this.db = dbFile.toAbsolutePath().normalize(); this.gate = gate; this.migrator = migrator;
    root = db.getParent().resolve("backup-management"); marker = root.resolve("restore-marker.json");
    metadata = root.resolve("last-backup.json"); Files.createDirectories(root); privatePath(root,true);
    try(java.util.stream.Stream<Path> entries=Files.list(root)) {
      for(Path entry:(Iterable<Path>)entries::iterator)
        if(Files.isDirectory(entry)&&entry.getFileName().toString().startsWith("preview-"))deleteTree(entry);
    }
    Path reference = root.resolve("reference-" + id() + ".db");
    try { migrator.migrate(reference); targetSchema = schemaVersion(reference); trustedStructure = structure(reference); }
    finally { deleteDatabase(reference); }
    if (targetSchema < 21 || targetSchema > MIGRATIONS.length) throw new IllegalStateException("备份功能不支持当前数据库版本。");
  }

  Map<String,Object> status() throws Exception {
    Map<String,Object> out = map("lastBackupAt", null, "lastBackupBy", null,
        "reminderDays", 7, "reminderDue", true, "maintenance", gate.isMaintaining());
    if (Files.isRegularFile(metadata)) {
      JsonObject saved = readJson(metadata);
      String at = string(saved,"lastBackupAt");
      out.put("lastBackupAt", at); out.put("lastBackupBy", string(saved,"lastBackupBy"));
      out.put("reminderDue", at == null || !Instant.parse(at).plus(Duration.ofDays(7)).isAfter(Instant.now()));
    }
    return out;
  }

  BackupFile download(long actorId, String username) throws Exception {
    Path work = Files.createTempDirectory(root, "download-");
    try {
      Path snapshot = work.resolve("ipam.db"); snapshot(db,snapshot);
      if (Files.size(snapshot) > MAX_UPLOAD_BYTES) throw problem(413,"BACKUP_TOO_LARGE","数据库超过当前备份恢复支持的 256 MB 上限。");
      String at = Instant.now().toString();
      JsonObject manifest = JSON.toJsonTree(map("format",FORMAT,"formatVersion",1,
          "backupCreatedAt",at,"createdBy",username,"schemaVersion",schemaVersion(snapshot),
          "databaseSha256",sha(snapshot),"databaseSize",Files.size(snapshot))).getAsJsonObject();
      Path archive = work.resolve("ipam-backup.zip");
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
        zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        zip.putNextEntry(new ZipEntry("ipam.db")); Files.copy(snapshot,zip); zip.closeEntry();
      }
      force(archive); audit(db,actorId,username,"DOWNLOAD_BACKUP","SUCCESS",null);
      recordBackup(at,username);
      return new BackupFile(work,archive,"ipam-backup-" + at.replaceAll("[^0-9]", "") + ".zip");
    } catch (Exception e) { deleteTree(work); throw e; }
  }

  Map<String,Object> preview(InputStream raw, String fileName, long actorId, String username) throws Exception {
    cleanupExpired();
    if(previews.size()>=8)throw problem(409,"RESTORE_PREVIEW_LIMIT","已有多个恢复预览，请使用现有预览或一小时后重试。");
    Path work = Files.createTempDirectory(root,"preview-");
    try {
      Path upload = work.resolve("uploaded.bin");
      try (OutputStream out = Files.newOutputStream(upload)) { copyLimited(raw,out,MAX_UPLOAD_BYTES); }
      Path candidate = work.resolve("candidate.db"); JsonObject manifest = null;
      if (isSqlite(upload)) Files.copy(upload,candidate);
      else manifest = extractArchive(upload,candidate);
      checkIntegrity(candidate); int source = validateHistory(candidate);
      if (source < 21 || source > targetSchema) throw problem(400,"BACKUP_VERSION_UNSUPPORTED","只支持 V021 及当前版本的完整数据库备份。");
      if (manifest != null) {
        if (!FORMAT.equals(string(manifest,"format")) || integer(manifest,"formatVersion") != 1
            || integer(manifest,"schemaVersion") != source || !sha(candidate).equals(string(manifest,"databaseSha256"))
            || manifest.get("databaseSize").getAsLong() != Files.size(candidate))
          throw problem(400,"BACKUP_MANIFEST_INVALID","备份清单、数据库版本或校验和不匹配。");
      }
      migrator.migrate(candidate);
      checkIntegrity(candidate); validateHistory(candidate);
      if (schemaVersion(candidate) != targetSchema || !trustedStructure.equals(structure(candidate)))
        throw problem(400,"BACKUP_SCHEMA_INVALID","备份数据库结构与本系统不兼容。");
      ensureAdmin(candidate); checkpoint(candidate);
      String uploadId = id(); Instant expires = Instant.now().plus(PREVIEW_TTL);
      String created = manifest == null ? null : string(manifest,"backupCreatedAt");
      if (created != null) Instant.parse(created);
      Preview p = new Preview(work,candidate,actorId,expires,sha(candidate)); previews.put(uploadId,p);
      return map("uploadId",uploadId,"backupCreatedAt",created,"sourceSchema",source,"targetSchema",targetSchema,
          "expiresAt",expires.toString(),"counts",counts(candidate),"requiresRelogin",true,
          "message","恢复将覆盖全部系统数据和账号，并使所有登录会话失效；请使用备份时的账号密码重新登录。");
    } catch (PhaseTwoService.Problem e) { deleteTree(work); throw e; }
    catch (Exception e) { deleteTree(work); throw problem(400,"BACKUP_INVALID","文件不是有效、完整且兼容的系统备份。"); }
  }

  Map<String,Object> confirm(String uploadId,long actorId,String username) throws Exception {
    Preview p = previews.get(uploadId);
    if (p == null || p.actor != actorId || !p.expires.isAfter(Instant.now()))
      throw problem(409,"RESTORE_PREVIEW_EXPIRED","恢复预览不存在、已过期或不属于当前管理员，请重新上传。");
    if (!restoreQueued.compareAndSet(false,true)) throw problem(409,"RESTORE_IN_PROGRESS","已有恢复操作正在执行。");
    String jobId = id();
    try {
      if (!previews.remove(uploadId,p)) throw problem(409,"RESTORE_PREVIEW_EXPIRED","该恢复预览已使用，请重新上传。");
      job(jobId,"QUEUED","恢复已安排，正在等待现有请求完成。");
      executor.execute(() -> restore(jobId,p,actorId,username));
      return map("jobId",jobId,"status","QUEUED");
    } catch (Exception e) { restoreQueued.set(false); throw e; }
  }

  void cancel(String uploadId,long actorId) throws Exception {
    Preview p=previews.get(uploadId);
    if(p==null)return;
    if(p.actor!=actorId)throw problem(403,"RESTORE_PREVIEW_FORBIDDEN","恢复预览不属于当前管理员。");
    if(previews.remove(uploadId,p))deleteTree(p.work);
  }

  Map<String,Object> jobStatus(String jobId) throws Exception {
    if (jobId == null || !jobId.matches("[0-9a-f]{32}")) throw problem(404,"RESTORE_JOB_NOT_FOUND","恢复任务不存在。");
    Path path = root.resolve("job-"+jobId+".json");
    if (!Files.isRegularFile(path)) throw problem(404,"RESTORE_JOB_NOT_FOUND","恢复任务不存在。");
    JsonObject saved = readJson(path);
    if(Files.isRegularFile(marker)) {
      JsonObject state=readJson(marker);
      if(jobId.equals(string(state,"jobId"))&&"COMPLETED".equals(string(state,"phase"))) {
        saved.addProperty("status","SUCCEEDED");saved.addProperty("message","全部数据已恢复，请使用备份时的账号密码重新登录。");
      }
    }
    if("SUCCEEDED".equals(string(saved,"status"))&&gate.isMaintaining()) {
      saved.addProperty("status","RESTORING");
      saved.addProperty("message","数据已恢复，正在完成收尾，请稍后重新登录。");
    }
    return map("jobId",jobId,"status",string(saved,"status"),"message",string(saved,"message"),
        "requiresRelogin","SUCCEEDED".equals(string(saved,"status")));
  }

  private void restore(String jobId,Preview preview,long actorId,String username) {
    Path rollback = root.resolve("before-restore-"+jobId+".db");
    boolean marked = false,committed = false;
    try (MaintenanceGate.Permit permit = gate.enterMaintenance()) {
      try {
        job(jobId,"RESTORING","系统正在恢复，业务操作暂时不可用。");
        if (!sha(preview.candidate).equals(preview.digest)) throw new IOException("Restore preview changed");
        snapshot(db,rollback); force(rollback);
        Path candidate = root.resolve("restore-"+jobId+".db"); Files.copy(preview.candidate,candidate);
        revokeSessions(candidate); audit(candidate,null,username,"RESTORE_BACKUP","SUCCESS",jobId,actorId);
        checkIntegrity(candidate); ensureAdmin(candidate); checkpoint(candidate); force(candidate);
        JsonObject state = JSON.toJsonTree(map("jobId",jobId,"phase","READY",
            "rollbackName",rollback.getFileName().toString(),"rollbackSha256",sha(rollback),
            "candidateName",candidate.getFileName().toString())).getAsJsonObject();
        writeJson(marker,state); marked = true;
        checkpoint(db); clearSidecars(db);
        atomicReplace(candidate,db);
        state.addProperty("phase","SWAPPED"); writeJson(marker,state);
        checkIntegrity(db); ensureAdmin(db);
        state.addProperty("phase","COMPLETED"); writeJson(marker,state);
        committed=true;
        job(jobId,"SUCCEEDED","全部数据已恢复，请使用备份时的账号密码重新登录。");
        try{Files.deleteIfExists(marker);syncDirectory(root);}catch(Exception cleanup){System.err.println("Completed restore marker cleanup deferred, job="+jobId);}
      } catch (Exception failure) {
        if(committed) {
          System.err.println("Restore completed; job status persistence deferred, job="+jobId);failure.printStackTrace(System.err);return;
        }
        boolean safe = true;
        if(!marked&&Files.exists(marker)) {
          try {
            JsonObject actual=readJson(marker);
            if(jobId.equals(string(actual,"jobId")))marked=true;
            else {safe=false;gate.keepBlocked();}
          } catch(Exception unreadable){safe=false;gate.keepBlocked();failure.addSuppressed(unreadable);}
        }
        if (marked) {
          try { restoreSnapshot(db,rollback); Files.deleteIfExists(marker); syncDirectory(root); }
          catch (Exception rollbackFailure) { safe = false; gate.keepBlocked(); failure.addSuppressed(rollbackFailure); }
        }
        try { job(jobId,"FAILED",safe ? "恢复失败，原数据库已保留或自动恢复，您可以继续使用系统。"
            : "恢复失败，系统保持维护状态；请联系管理员检查恢复日志。"); }
        catch (Exception statusFailure) { failure.addSuppressed(statusFailure); }
        System.err.println("IPAM restore failed, job="+jobId+", rollbackSafe="+safe); failure.printStackTrace(System.err);
      }
    } catch (Exception e) {
      try { job(jobId,"FAILED","恢复未开始，原数据库未改变，请稍后重试。"); }
      catch (Exception ignored) { e.addSuppressed(ignored); }
    } finally {
      restoreQueued.set(false);
      try { deleteTree(preview.work); } catch (Exception e) { System.err.println("Restore temporary cleanup failed"); }
    }
  }

  /** Called before any application connection or migration is opened. */
  static void recoverInterrupted(Path dbFile) throws Exception {
    Path database = dbFile.toAbsolutePath().normalize(), root = database.getParent().resolve("backup-management");
    Path marker = root.resolve("restore-marker.json");
    if (!Files.isRegularFile(marker)) { abandonInterruptedJobs(root);return; }
    JsonObject state = readJson(marker); String jobId = string(state,"jobId");
    if (jobId == null || !jobId.matches("[0-9a-f]{32}")) throw new IOException("Invalid restore recovery marker");
    String rollbackName = string(state,"rollbackName");
    if (!("before-restore-"+jobId+".db").equals(rollbackName)) throw new IOException("Invalid restore rollback path");
    Path rollback = root.resolve(rollbackName);
    boolean completed = "COMPLETED".equals(string(state,"phase"));
    if (completed) {
      try { checkIntegrity(database); ensureAdmin(database); }
      catch (Exception invalid) { completed = false; }
    }
    if (!completed) {
      if (!Files.isRegularFile(rollback) || !sha(rollback).equals(string(state,"rollbackSha256")))
        throw new IOException("Restore rollback snapshot is missing or invalid");
      restoreSnapshot(database,rollback);
    }
    writeJson(root.resolve("job-"+jobId+".json"),JSON.toJsonTree(map("status",completed?"SUCCEEDED":"FAILED",
        "message",completed?"全部数据已恢复，请重新登录。":"恢复被中断，启动时已自动恢复原数据库。")).getAsJsonObject());
    Files.deleteIfExists(marker); syncDirectory(root);abandonInterruptedJobs(root);
  }

  private static void abandonInterruptedJobs(Path root) throws Exception {
    if(!Files.isDirectory(root))return;
    try(java.util.stream.Stream<Path> entries=Files.list(root)) {
      for(Path path:(Iterable<Path>)entries::iterator) {
        if(!path.getFileName().toString().matches("job-[0-9a-f]{32}\\.json"))continue;
        JsonObject saved=readJson(path);String status=string(saved,"status");
        if("QUEUED".equals(status)||"RESTORING".equals(status))
          writeJson(path,JSON.toJsonTree(map("status","FAILED","message","恢复被中断，原数据库未被替换。")).getAsJsonObject());
      }
    }
  }

  private static void restoreSnapshot(Path db,Path snapshot) throws Exception {
    checkIntegrity(snapshot); Path staged = db.resolveSibling(".ipam-rollback-"+id()+".db");
    try { Files.copy(snapshot,staged); force(staged); clearSidecars(db); atomicReplace(staged,db); checkIntegrity(db); }
    finally { deleteDatabase(staged); }
  }

  private void cleanupExpired() throws Exception {
    for (Map.Entry<String,Preview> item : previews.entrySet()) {
      Preview p=item.getValue();
      if (!p.expires.isAfter(Instant.now()) && previews.remove(item.getKey(),p)) deleteTree(p.work);
    }
  }
  private synchronized void recordBackup(String at,String username) throws Exception {
    if(Files.isRegularFile(metadata)) {
      String previous=string(readJson(metadata),"lastBackupAt");
      if(previous!=null&&Instant.parse(previous).isAfter(Instant.parse(at)))return;
    }
    writeJson(metadata,JSON.toJsonTree(map("lastBackupAt",at,"lastBackupBy",username)).getAsJsonObject());
  }
  private void job(String id,String status,String message) throws Exception {
    writeJson(root.resolve("job-"+id+".json"),JSON.toJsonTree(map("status",status,"message",message)).getAsJsonObject());
  }

  private int validateHistory(Path path) throws Exception {
    int version=0;
    try (Connection c=open(path); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT version,checksum FROM schema_history ORDER BY version")) {
      while (r.next()) {
        version++;
        if (version > MIGRATIONS.length || !String.format(Locale.ROOT,"%03d",version).equals(r.getString(1)))
          throw problem(400,"BACKUP_VERSION_UNSUPPORTED","备份迁移版本不连续或高于当前系统版本。");
        String resource="/db/migration/V"+String.format(Locale.ROOT,"%03d",version)+"__"+MIGRATIONS[version-1]+".sql";
        try (InputStream in=BackupService.class.getResourceAsStream(resource)) {
          boolean legacy13=version==13&&"b54358d0baecd20c8cd9811c0efe58fc8d4e8dddd73515ca0ecce90b7be55415".equals(r.getString(2));
          if (in==null || (!sha(in).equals(r.getString(2))&&!legacy13)) throw problem(400,"BACKUP_MIGRATION_INVALID","备份迁移记录校验失败。");
        }
      }
    }
    return version;
  }
  private static int schemaVersion(Path path) throws Exception {
    try (Connection c=open(path); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT max(CAST(version AS INTEGER)) FROM schema_history")) {
      if (!r.next()) throw new IOException("Missing schema history"); return r.getInt(1);
    }
  }
  private static Map<String,String> structure(Path path) throws Exception {
    Map<String,String> result=new TreeMap<>();
    try (Connection c=open(path); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name")) {
      while(r.next()) result.put(r.getString(1)+":"+r.getString(2), r.getString(3)==null?"":r.getString(3).trim().replaceAll("\\s+"," "));
    }
    return result;
  }
  private static Map<String,Object> counts(Path path) throws Exception {
    String[] tables={"sys_user","branch","outlet","ip_range","ip_address_pool","ip_application","ip_assignment","ip_release_event","audit_log","assignment_import_job","assignment_import_row","outlet_directory","directory_import_job"};
    Map<String,Object> result=new LinkedHashMap<>();
    try (Connection c=open(path); Statement s=c.createStatement()) {
      for(String table:tables)try(ResultSet r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();result.put(table,r.getLong(1));}
    }
    return result;
  }
  private static void ensureAdmin(Path path) throws Exception {
    try(Connection c=open(path);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM sys_user WHERE role='ADMIN' AND is_initial_admin=1 AND is_active=1 AND deleted_at IS NULL")) {
      if(!r.next()||r.getLong(1)!=1)throw problem(400,"BACKUP_ADMIN_INVALID","备份中缺少唯一、有效的初始管理员账号。");
    }
  }
  private static void checkIntegrity(Path path) throws Exception {
    if(!Files.isRegularFile(path)||!isSqlite(path))throw problem(400,"BACKUP_INVALID","文件不是 SQLite 数据库。");
    try(Connection c=open(path);Statement s=c.createStatement()) {
      try(ResultSet r=s.executeQuery("PRAGMA integrity_check")){if(!r.next()||!"ok".equals(r.getString(1))||r.next())throw problem(400,"BACKUP_INTEGRITY_INVALID","备份完整性校验失败。");}
      try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw problem(400,"BACKUP_FOREIGN_KEY_INVALID","备份包含无效的数据关联。");}
    }
  }
  private static void snapshot(Path source,Path target) throws Exception {
    if(!Files.isRegularFile(source)||Files.exists(target))throw new IOException("Invalid snapshot source/target");
    try(Connection c=open(source);Statement s=c.createStatement()){s.execute("VACUUM INTO '"+target.toString().replace("'","''")+"'");}
    privatePath(target,false);checkIntegrity(target);
  }
  private static void checkpoint(Path path) throws Exception {
    try(Connection c=open(path);Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
      if(r.next()&&r.getInt(1)!=0)throw new IOException("Database checkpoint is busy");
    }
  }
  private static void revokeSessions(Path path) throws Exception {
    try(Connection c=open(path)) {
      c.setAutoCommit(false);
      try {
        try(PreparedStatement p=c.prepareStatement("UPDATE sys_session SET revoked_at=? WHERE revoked_at IS NULL")){p.setString(1,Instant.now().toString());p.executeUpdate();}
        try(Statement s=c.createStatement()) {
          s.executeUpdate("UPDATE bulk_import_job SET status='EXPIRED' WHERE status IN ('VALIDATING','PREVIEW_READY')");
          s.executeUpdate("UPDATE outlet_plan_import_job SET status='EXPIRED' WHERE status='PREVIEW_READY'");
          boolean hasAssignmentImports;
          try(ResultSet r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='assignment_import_job'")){hasAssignmentImports=r.next();}
          try(ResultSet r=s.executeQuery("SELECT 1 FROM sqlite_master WHERE name='directory_import_job'")) {
            if(r.next()) { try(Statement expire=c.createStatement()){expire.executeUpdate("UPDATE directory_import_job SET status='EXPIRED' WHERE status='READY'");} }
          }
          if(hasAssignmentImports)s.executeUpdate("UPDATE assignment_import_job SET status='EXPIRED' WHERE status='PREVIEW_READY'");
        }
        c.commit();
      } catch(Exception e){c.rollback();throw e;}
    }
  }
  private static void audit(Path path,Long actor,String username,String action,String result,String jobId) throws Exception {
    audit(path,actor,username,action,result,jobId,actor);
  }
  private static void audit(Path path,Long actor,String username,String action,String result,String jobId,Long originalActor) throws Exception {
    try(Connection c=open(path);PreparedStatement p=c.prepareStatement("INSERT INTO audit_log(actor_user_id,action,target_type,target_id,result,detail_json) VALUES(?,?, 'DATABASE',?,?,?)")) {
      if(actor==null)p.setNull(1,Types.INTEGER);else p.setLong(1,actor);
      p.setString(2,action);p.setString(3,jobId==null?"full-backup":jobId);p.setString(4,result);
      p.setString(5,JSON.toJson(map("operatorUserId",originalActor,"operatorUsername",username)));p.executeUpdate();
    }
  }
  private static Connection open(Path path) throws Exception {
    Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);
    try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA synchronous=FULL");}
    catch(Exception e){c.close();throw e;} return c;
  }
  private static JsonObject extractArchive(Path archive,Path candidate) throws Exception {
    JsonObject manifest=null; boolean database=false;Set<String> names=new HashSet<>();
    try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(archive))) {
      ZipEntry entry;while((entry=zip.getNextEntry())!=null) {
        String name=entry.getName();
        if(entry.isDirectory()||!names.add(name)||(!"manifest.json".equals(name)&&!"ipam.db".equals(name)))
          throw problem(400,"BACKUP_ARCHIVE_INVALID","备份包只能包含唯一的 manifest.json 和 ipam.db。");
        if("manifest.json".equals(name)) { ByteArrayOutputStream out=new ByteArrayOutputStream();copyLimited(zip,out,65536);manifest=JsonParser.parseString(out.toString(StandardCharsets.UTF_8.name())).getAsJsonObject(); }
        else { try(OutputStream out=Files.newOutputStream(candidate)){copyLimited(zip,out,MAX_UPLOAD_BYTES);}database=true; }
        zip.closeEntry();
      }
    }
    if(manifest==null||!database)throw problem(400,"BACKUP_ARCHIVE_INVALID","备份包缺少清单或完整数据库。");
    return manifest;
  }
  private static boolean isSqlite(Path path) throws Exception {
    byte[] expected="SQLite format 3\000".getBytes(StandardCharsets.US_ASCII),got=new byte[16];
    try(InputStream in=Files.newInputStream(path)){return in.readNBytes(got,0,16)==16&&Arrays.equals(expected,got);}
  }
  private static void copyLimited(InputStream in,OutputStream out,long max) throws Exception {
    byte[] buf=new byte[65536];long total=0;int n;
    while((n=in.read(buf))!=-1){total+=n;if(total>max)throw problem(413,"BACKUP_TOO_LARGE","备份文件超过大小上限。");out.write(buf,0,n);}
    if(total==0)throw problem(400,"BACKUP_EMPTY","备份文件为空。");
  }
  private static void atomicReplace(Path source,Path target) throws Exception {
    privatePath(source,false);force(source);
    Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    syncDirectory(target.getParent());if(!source.getParent().equals(target.getParent()))syncDirectory(source.getParent());
  }
  private static void writeJson(Path target,JsonObject value) throws Exception {
    Path temp=target.resolveSibling(target.getFileName()+"."+id()+".tmp");
    try { byte[] bytes=JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
      try(FileChannel c=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}
      atomicReplace(temp,target);
    } finally {Files.deleteIfExists(temp);}
  }
  private static JsonObject readJson(Path path) throws Exception {
    if(Files.size(path)>65536)throw new IOException("Invalid metadata size");
    return JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
  }
  private static void force(Path path) throws Exception {try(FileChannel c=FileChannel.open(path,StandardOpenOption.WRITE)){c.force(true);}}
  private static void syncDirectory(Path path) {try(FileChannel c=FileChannel.open(path,StandardOpenOption.READ)){c.force(true);}catch(Exception ignored){/* Not supported by every host filesystem. */}}
  private static void privatePath(Path path,boolean directory) throws IOException {
    if(Files.getFileStore(path).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
  }
  private static void clearSidecars(Path db) throws Exception {Files.deleteIfExists(db.resolveSibling(db.getFileName()+"-wal"));Files.deleteIfExists(db.resolveSibling(db.getFileName()+"-shm"));}
  private static void deleteDatabase(Path path) throws Exception {Files.deleteIfExists(path);clearSidecars(path);}
  private static void deleteTree(Path root) throws Exception {
    if(!Files.exists(root))return;
    try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}
  }
  private static String sha(Path path) throws Exception {try(InputStream in=Files.newInputStream(path)){return sha(in);}}
  private static String sha(InputStream in) throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);StringBuilder out=new StringBuilder();for(byte x:digest.digest())out.append(String.format(Locale.ROOT,"%02x",x&255));return out.toString();}
  private static String string(JsonObject object,String name) {return !object.has(name)||object.get(name).isJsonNull()?null:object.get(name).getAsString();}
  private static int integer(JsonObject object,String name) {return object.get(name).getAsInt();}
  private static String id(){return UUID.randomUUID().toString().replace("-","");}
  private static Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
  private static PhaseTwoService.Problem problem(int status,String code,String message){return new PhaseTwoService.Problem(status,code,message);}
  @Override public void close(){executor.shutdown();}
  static final class BackupFile implements AutoCloseable {
    final Path path; final String fileName; private final Path work;
    BackupFile(Path work,Path path,String fileName){this.work=work;this.path=path;this.fileName=fileName;}
    @Override public void close() throws Exception {deleteTree(work);}
  }
  private static final class Preview {
    final Path work,candidate;final long actor;final Instant expires;final String digest;
    Preview(Path work,Path candidate,long actor,Instant expires,String digest){this.work=work;this.candidate=candidate;this.actor=actor;this.expires=expires;this.digest=digest;}
  }
}
