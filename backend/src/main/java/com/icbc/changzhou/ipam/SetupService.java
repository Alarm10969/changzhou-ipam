package com.icbc.changzhou.ipam;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** A resumable readiness guide. Completion is derived from actual records, never client checkboxes. */
final class SetupService {
  private final Path file;
  SetupService(Path file) { this.file=file; }

  private Connection open() throws SQLException {
    Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);
    try(Statement s=c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000"); }
    return c;
  }
  private static long count(Connection c,String sql)throws SQLException {
    try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){return r.next()?r.getLong(1):0;}
  }
  private static String config(Connection c,String key)throws SQLException {
    try(PreparedStatement p=c.prepareStatement("SELECT config_value FROM system_config WHERE config_key=?")){
      p.setString(1,key);try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):"";}
    }
  }
  Map<String,Object> status()throws Exception {try(Connection c=open()){return status(c);}}
  private Map<String,Object> status(Connection c)throws Exception {
    Map<String,Object> out=new LinkedHashMap<>();
    long version=count(c,"SELECT version_no FROM planning_rule_version WHERE is_active=1");
    boolean rules=String.valueOf(version).equals(config(c,"setup.rule_version"));
    long branches=count(c,"SELECT count(*) FROM branch WHERE is_active=1 AND deleted_at IS NULL");
    long outlets=count(c,"SELECT count(*) FROM outlet o JOIN branch b ON b.id=o.branch_id WHERE o.is_active=1 AND o.deleted_at IS NULL AND b.is_active=1 AND b.deleted_at IS NULL");
    long assets=count(c,"SELECT count(*) FROM asset_definition WHERE is_active=1 AND deleted_at IS NULL");
    long users=count(c,"SELECT count(*) FROM sys_user WHERE role='TECH_ADMIN' AND is_active=1 AND deleted_at IS NULL");
    long planned=count(c,"SELECT count(*) FROM (SELECT o.id FROM outlet o JOIN branch b ON b.id=o.branch_id JOIN ip_range r ON r.outlet_id=o.id WHERE o.is_active=1 AND o.deleted_at IS NULL AND b.is_active=1 AND b.deleted_at IS NULL AND r.is_active=1 AND r.deleted_at IS NULL AND r.range_type IN ('PRODUCTION','NETWORK_MANAGEMENT','EXTENSION','MONITORING','TEST','OFFICE') GROUP BY o.id HAVING count(DISTINCT r.range_type)=6)");
    long pool=count(c,"SELECT count(*) FROM ip_address_pool p JOIN ip_range r ON r.id=p.ip_range_id JOIN outlet o ON o.id=r.outlet_id JOIN branch b ON b.id=o.branch_id WHERE p.deleted_at IS NULL AND p.is_active=1 AND r.deleted_at IS NULL AND r.is_active=1 AND o.deleted_at IS NULL AND o.is_active=1 AND b.deleted_at IS NULL AND b.is_active=1");
    out.put("rulesReviewed",rules);out.put("branchCount",branches);out.put("outletCount",outlets);
    out.put("assetCount",assets);out.put("techUserCount",users);out.put("plannedOutletCount",planned);out.put("poolCount",pool);
    // Readiness means the first complete outlet can be trialled; it does not certify all outlets or physical networking.
    out.put("ready",rules&&branches>0&&outlets>0&&assets>0&&users>0&&planned>0&&pool>0);
    out.put("completedAt",config(c,"setup.completed_at"));
    out.put("bindAddress",System.getenv().getOrDefault("IPAM_BIND","127.0.0.1"));
    out.put("port",System.getenv().getOrDefault("IPAM_PORT","18080"));
    return out;
  }
  Map<String,Object> complete(long actor)throws Exception {
    synchronized(PhaseTwoService.WRITE_LOCK){try(Connection c=open()){
      c.setAutoCommit(false);
      try {
        Map<String,Object> out=status(c);
        if(!Boolean.TRUE.equals(out.get("ready")))throw PhaseFourService.problem("SETUP_NOT_READY","尚未完成规则确认、启用机构、资产、科技管理员和首个网点六类地址规划，请按向导补齐。");
        String time=Instant.now().toString();
        try(PreparedStatement p=c.prepareStatement("INSERT INTO system_config(config_key,config_value,updated_by) VALUES('setup.completed_at',?,?) ON CONFLICT(config_key) DO UPDATE SET config_value=excluded.config_value,updated_by=excluded.updated_by,updated_at=excluded.config_value")){
          p.setString(1,time);p.setLong(2,actor);p.executeUpdate();
        }
        try(PreparedStatement p=c.prepareStatement("INSERT INTO audit_log(actor_user_id,action,target_type,result,detail_json) VALUES(?,'COMPLETE_SETUP','SYSTEM','SUCCESS','{}')")){
          p.setLong(1,actor);p.executeUpdate();
        }
        c.commit();out.put("completedAt",time);return out;
      } catch(Exception e){c.rollback();throw e;}
    }}
  }
}
