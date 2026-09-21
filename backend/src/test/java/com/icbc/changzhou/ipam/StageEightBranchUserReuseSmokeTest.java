package com.icbc.changzhou.ipam;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import org.mindrot.jbcrypt.BCrypt;

/** Regression gate for soft-deleted branch/user reuse and corrected-role login. */
public final class StageEightBranchUserReuseSmokeTest {
  public static void main(String[] args)throws Exception {
    Path work=Files.createTempDirectory("ipam-stage8-"),db=work.resolve("branch-user-reuse.db");
    Class<?> type=Class.forName("com.icbc.changzhou.ipam.IpamApplication$Database");Constructor<?> ctor=type.getDeclaredConstructor(Path.class);ctor.setAccessible(true);Object runtime=ctor.newInstance(db);Method migrate=type.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(runtime);
    try(Connection c=open(db);PreparedStatement p=c.prepareStatement("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES(?,?,?,?,1,0,?)")){
      count(c,"SELECT count(*) FROM schema_history WHERE version='016'",1);
      insertUser(p,"admin8",BCrypt.hashpw("AdminBase123",BCrypt.gensalt()),"初始管理员","ADMIN",1);
      insertUser(p,"reuseuser",BCrypt.hashpw("TechOld123",BCrypt.gensalt()),"原科技管理员","TECH_ADMIN",0);
      try(Statement s=c.createStatement()){s.execute("INSERT INTO branch(code,name,is_active) VALUES('BREUSE','原支行',1)");}
    }

    new PhaseTwoService(db).deleteBranch(1L,1L);
    Method deleteUser=type.getDeclaredMethod("deleteUser",long.class);deleteUser.setAccessible(true);deleteUser.invoke(runtime,2L);
    try(Connection c=open(db)){
      count(c,"SELECT count(*) FROM branch WHERE code='BREUSE' AND deleted_at IS NOT NULL",1);
      count(c,"SELECT count(*) FROM sys_user WHERE username='reuseuser' AND deleted_at IS NOT NULL",1);
    }

    Method visibleUsernameExists=type.getDeclaredMethod("visibleUsernameExists",String.class);visibleUsernameExists.setAccessible(true);
    if((boolean)visibleUsernameExists.invoke(runtime,"reuseuser"))throw new AssertionError("deleted username must be available for recreation");
    Method createUser=type.getDeclaredMethod("createUser",String.class,String.class,String.class,String.class,long.class);createUser.setAccessible(true);
    long replacementId=(long)createUser.invoke(runtime,"reuseuser","重建管理员","AdminNew123","ADMIN",1L);
    if(replacementId==2L)throw new AssertionError("replacement account must have a distinct identity");
    if(!(boolean)visibleUsernameExists.invoke(runtime,"reuseuser"))throw new AssertionError("new account must reserve the visible username");

    Method login=type.getDeclaredMethod("login",String.class,String.class);login.setAccessible(true);
    assertLogin(login,runtime,"AdminNew123",replacementId,"ADMIN");
    if(login.invoke(runtime,"reuseuser","TechOld123")!=null)throw new AssertionError("deleted account password must not authenticate the replacement account");

    try(Connection c=open(db);Statement s=c.createStatement()){
      s.execute("UPDATE sys_user SET failed_login_count=5,locked_until='"+Instant.now().plusSeconds(3600)+"' WHERE id="+replacementId);
    }
    if(login.invoke(runtime,"reuseuser","AdminNew123")!=null)throw new AssertionError("locked account unexpectedly authenticated before reset");
    Method resetPassword=type.getDeclaredMethod("resetPassword",long.class,String.class);resetPassword.setAccessible(true);resetPassword.invoke(runtime,replacementId,"ResetAdmin456");
    try(Connection c=open(db)){
      count(c,"SELECT failed_login_count FROM sys_user WHERE id="+replacementId,0);
      count(c,"SELECT count(*) FROM sys_user WHERE id="+replacementId+" AND locked_until IS NULL",1);
      count(c,"SELECT count(*) FROM sys_user WHERE username='reuseuser' AND deleted_at IS NULL",1);
      count(c,"SELECT count(*) FROM sys_user WHERE username='reuseuser' AND deleted_at IS NOT NULL",1);
      count(c,"SELECT count(*) FROM branch WHERE code='BREUSE' AND deleted_at IS NULL",0);
      try(Statement s=c.createStatement()){
        s.execute("INSERT INTO branch(code,name,is_active) VALUES('BREUSE','重建支行',1)");
        count(c,"SELECT count(*) FROM branch WHERE code='BREUSE' AND deleted_at IS NULL",1);
        boolean branchDuplicate=false;try{s.execute("INSERT INTO branch(code,name,is_active) VALUES('BREUSE','重复支行',1)");}catch(SQLException expected){branchDuplicate=true;}if(!branchDuplicate)throw new AssertionError("visible branch code must remain blocked");
        boolean userDuplicate=false;try{s.execute("INSERT INTO sys_user(username,password_hash,real_name,role,is_active,must_change_password,is_initial_admin) VALUES('reuseuser','x','重复管理员','ADMIN',1,0,0)");}catch(SQLException expected){userDuplicate=true;}if(!userDuplicate)throw new AssertionError("visible username must remain blocked");
        try(ResultSet r=s.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new AssertionError("foreign-key check failed after branch/user reuse: "+r.getString(1));}
      }
    }
    assertLogin(login,runtime,"ResetAdmin456",replacementId,"ADMIN");
    String app=Files.readString(Paths.get("backend","src","main","java","com","icbc","changzhou","ipam","IpamApplication.java"));
    if(!app.contains("BRANCH_CODE_DUPLICATE")||!app.contains("USERNAME_DUPLICATE"))throw new AssertionError("active duplicates must return business errors");
    System.out.println("PASS|STAGE8_SOFT_DELETED_BRANCH_AND_USER_REUSE_LOGIN_RESET|"+work);
  }
  private static void insertUser(PreparedStatement p,String username,String hash,String name,String role,int initialAdmin)throws Exception{p.setString(1,username);p.setString(2,hash);p.setString(3,name);p.setString(4,role);p.setInt(5,initialAdmin);p.executeUpdate();}
  private static void assertLogin(Method login,Object runtime,String password,long expectedId,String expectedRole)throws Exception{
    Object result=login.invoke(runtime,"reuseuser",password);if(result==null)throw new AssertionError("recreated account could not log in");
    Field userField=result.getClass().getDeclaredField("user");userField.setAccessible(true);Object principal=userField.get(result);
    Field idField=principal.getClass().getDeclaredField("id"),roleField=principal.getClass().getDeclaredField("role");idField.setAccessible(true);roleField.setAccessible(true);
    if(idField.getLong(principal)!=expectedId||!expectedRole.equals(roleField.get(principal)))throw new AssertionError("login selected the wrong account identity or role");
  }
  private static Connection open(Path db)throws Exception{Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);try(Statement s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");}return c;}
  private static void count(Connection c,String sql,long expected)throws Exception{try(ResultSet r=c.createStatement().executeQuery(sql)){r.next();if(r.getLong(1)!=expected)throw new AssertionError(sql+" expected="+expected+" actual="+r.getLong(1));}}
}
