package com.icbc.changzhou.ipam;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;

/** Regression gate for reusing codes of soft-deleted branches and user accounts. */
public final class StageEightBranchUserReuseSmokeTest {
  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("ipam-stage8-"),db=work.resolve("branch-user-reuse.db");
    Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");Constructor<?> ctor=type.getDeclaredConstructor(Path.class);ctor.setAccessible(true);Object runtime=ctor.newInstance(db);Method migrate=type.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(runtime);
    try(Connection c=open(db);Statement s=c.createStatement()){
      count(c,"SELECT count(*) FROM schema_history WHERE version='016'",1);
      s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('admin8','x','初始管理员','ADMIN',1,0,1)");
      s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('reuseuser','x','原科技管理员','TECH_ADMIN',1,0,0)");
      s.execute("INSERT INTO branch(code,name,is_active) VALUES('BREUSE','原支行',1)");
    }
    new PhaseTwoService(db).deleteBranch(1L,1L);
    Method deleteUser=type.getDeclaredMethod("deleteUser",long.class);deleteUser.setAccessible(true);deleteUser.invoke(runtime,2L);
    try(Connection c=open(db);Statement s=c.createStatement()){
      count(c,"SELECT count(*) FROM branch WHERE code='BREUSE' AND deleted_at IS NOT NULL",1);
      count(c,"SELECT count(*) FROM sys_user WHERE username='reuseuser' AND deleted_at IS NOT NULL",1);
      s.execute("INSERT INTO branch(code,name,is_active) VALUES('BREUSE','重建支行',1)");
      s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('reuseuser','x','重建科技管理员','TECH_ADMIN',1,0,0)");
      count(c,"SELECT count(*) FROM branch WHERE code='BREUSE' AND deleted_at IS NULL",1);
      count(c,"SELECT count(*) FROM sys_user WHERE username='reuseuser' AND deleted_at IS NULL",1);
      boolean branchDuplicate=false;try{s.execute("INSERT INTO branch(code,name,is_active) VALUES('BREUSE','重复支行',1)");}catch(SQLException expected){branchDuplicate=true;}if(!branchDuplicate)throw new AssertionError("visible branch code must remain blocked");
      boolean userDuplicate=false;try{s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('reuseuser','x','重复科技管理员','TECH_ADMIN',1,0,0)");}catch(SQLException expected){userDuplicate=true;}if(!userDuplicate)throw new AssertionError("visible username must remain blocked");
      try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new AssertionError("foreign-key check failed after branch/user reuse: "+r.getString(1));}
    }
    String app=Files.readString(Paths.get("backend","src","main","java","com","icbc","changzhou","ipam","IpamApplication.java"));
    if(!app.contains("BRANCH_CODE_DUPLICATE")||!app.contains("USERNAME_DUPLICATE"))throw new AssertionError("active duplicates must return business errors");
    System.out.println("PASS|STAGE8_SOFT_DELETED_BRANCH_AND_USER_REUSE|"+work);
  }
  private static Connection open(Path db)throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
  private static void count(Connection c,String sql,long expected)throws Exception{try(ResultSet r=c.createStatement().executeQuery(sql)){r.next();if(r.getLong(1)!=expected)throw new AssertionError(sql+" expected="+expected+" actual="+r.getLong(1));}}
}
