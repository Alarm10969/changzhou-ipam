import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.jar.*;

public class LegacyPcUpgradeProbe {
  public static void main(String[] args) throws Exception {
    if(args[0].equals("seed-ui")) {
      try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+args[1]);Statement s=c.createStatement()) {
        for(int i=0;i<2;i++)s.execute("INSERT INTO ip_application(applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,device_type,status,applicant_username,category_id,subcategory_id,user_name,user_unified_auth_id,recommended_range_type,mapping_id,rule_version_id) SELECT applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,device_type,'PENDING',applicant_username,category_id,subcategory_id,user_name,user_unified_auth_id,recommended_range_type,mapping_id,rule_version_id FROM ip_application WHERE id=1");
        System.out.println("PASS|TWO_PENDING_UI_FIXTURES");
      }
      return;
    }
    Path db=Path.of(args[1]);
    Class<?> cls=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");
    Constructor<?> ctor=cls.getDeclaredConstructor(Path.class);ctor.setAccessible(true);
    Object runtime=ctor.newInstance(db);
    Method apply=cls.getDeclaredMethod("apply",Connection.class,String.class,String.class,String.class);apply.setAccessible(true);
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement();JarFile jar=new JarFile(args[0])) {
      s.execute("CREATE TABLE schema_history(version TEXT PRIMARY KEY,description TEXT NOT NULL,checksum TEXT NOT NULL,installed_at TEXT DEFAULT CURRENT_TIMESTAMP)");
      for(int v=1;v<=12;v++) {
        final String prefix=String.format("db/migration/V%03d__",v);
        String resource=jar.stream().map(e->e.getName()).filter(n->n.startsWith(prefix)).findFirst().orElseThrow();
        apply.invoke(runtime,c,String.format("%03d",v),"QA fixture","/"+resource);
        if(v==2)s.execute("INSERT INTO sys_user(username,password_hash,real_name,role) VALUES('legacy','x','Legacy Admin','ADMIN')");
      }
      s.execute("INSERT INTO branch(code,name) VALUES('QA','Legacy Branch')");
      s.execute("INSERT INTO outlet(branch_id,code,name) VALUES(1,'QA','Legacy Outlet')");
      s.execute("INSERT INTO ip_application(applicant_user_id,applicant_name,unified_auth_id,branch_id,outlet_id,asset_type,asset_code,device_type,status) VALUES(1,'Legacy PC User','900000111',1,1,'TECH','LEGACY-PC-001','PC','PENDING')");
      System.out.println("PASS|VALID_V012_LEGACY_PC_INSERTED");
    }
    Method migrate=cls.getDeclaredMethod("migrate");migrate.setAccessible(true);
    try {migrate.invoke(runtime);System.out.println("PASS|LEGACY_PC_UPGRADE");}
    catch(InvocationTargetException e){System.out.println("FAIL|LEGACY_PC_UPGRADE|"+e.getCause());}
    try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT (SELECT count(*) FROM ip_application),(SELECT count(*) FROM schema_history WHERE version='013')")){r.next();System.out.println("AFTER|applications="+r.getInt(1)+"|V013="+r.getInt(2));}
  }
}
