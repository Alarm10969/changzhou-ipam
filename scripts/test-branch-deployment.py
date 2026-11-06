#!/usr/bin/env python3
"""Verify configuration and real start/stop/restart on an isolated copied release."""
import argparse, json, os, secrets, shutil, socket, sqlite3, subprocess, tempfile, time, urllib.request
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--java-home',required=True);p.add_argument('--package',default='.local-v029-build/branch-package');args=p.parse_args()
source=Path(args.package).resolve();java=Path(args.java_home)/'bin/java'
env={k:v for k,v in os.environ.items() if not k.startswith('IPAM_')};env['PATH']=str(java.parent)+os.pathsep+env['PATH']
with tempfile.TemporaryDirectory(prefix='ipam-deploy-') as tmp:
 root=Path(tmp)/'部署 测试';shutil.copytree(source,root)
 data=Path(tmp)/'业务 数据';data.mkdir();password='T'+secrets.token_hex(16)+'a9';secret=data/'.bootstrap';secret.write_text(password);secret.chmod(0o600)
 sock=socket.socket();sock.bind(('127.0.0.1',0));port=sock.getsockname()[1];sock.close()
 url='http://127.0.0.1:'+str(port)+'/api/v1/'
 def healthy():
  try:
   with urllib.request.urlopen(url+'health',timeout=1) as r:return r.status==200
  except Exception:return False
 log=open(Path(tmp)/'server.log','wb');process=None;started=False
 def run(*cmd,input=None):
  r=subprocess.run(cmd,cwd=root,env=env,input=input,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,errors="replace",timeout=25)
  if r.returncode:raise AssertionError(str(cmd)+': '+r.stdout)
  return r.stdout
 try:
  process=subprocess.Popen([str(java),'-jar','ipam-stage3.jar'],cwd=root,env=dict(env,IPAM_DATA_DIR=str(data),IPAM_STATIC_DIR=str(root/'frontend'),IPAM_BIND='127.0.0.1',IPAM_PORT=str(port),IPAM_ADMIN_USERNAME='deploy_test',IPAM_ADMIN_PASSWORD_FILE=str(secret)),stdout=log,stderr=log)
  deadline=time.monotonic()+20
  while not healthy():
   assert process.poll() is None and time.monotonic()<deadline,'bootstrap startup failed'
   time.sleep(.1)
  process.terminate();process.wait(timeout=10)
  assert not secret.exists()
  (root/'ipam.conf').write_text('IPAM_BIND=127.0.0.1\nIPAM_PORT='+str(port)+'\nIPAM_DATA_DIR='+str(data)+'\n')
  count=sqlite3.connect(data/'ipam.db').execute('select count(*) from sys_user').fetchone()[0]
  for _ in range(2):
   started=True;output=run('sh','./deploy/start-stage1.sh',input='')
   assert '不需要重复输入初始密码' in output,output
   assert healthy(),'start script did not honor config bind/port'
   assert not (root/'data').exists(),'configured directory ignored'
   request=urllib.request.Request(url+'auth/login',data=json.dumps({'username':'deploy_test','password':password}).encode(),headers={'Content-Type':'application/json'},method='POST')
   with urllib.request.urlopen(request) as r:assert r.status==200
   run('sh','./deploy/stop-stage1.sh');started=False
   assert not healthy(),'service remains reachable after stop'
  run('sh','./deploy/backup-restore.sh','backup',input='\n')
  assert len(list((data/'backups').glob('*.db')))==1,'backup did not use config data directory'
  with sqlite3.connect(data/'ipam.db') as c:assert c.execute('select count(*) from sys_user').fetchone()[0]==count
  assert not list(data.glob('.bootstrap-password*'))
  print('PASS|BRANCH_DEPLOY|real-java,custom-bind-port,unicode-space-path,existing-admin-no-prompt,repeat-start-stop,original-login-preserved,configured-backup')
 finally:
  if started:
   subprocess.run(['sh','./deploy/stop-stage1.sh'],cwd=root,env=env,capture_output=True,timeout=15)
  if process and process.poll() is None:process.terminate();process.wait(timeout=10)
  log.close()
