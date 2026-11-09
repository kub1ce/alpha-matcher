"""Finite, owned Windows demo lifecycle. Never targets another project's process/database."""
from pathlib import Path
import os,sys,json,secrets,subprocess,socket,time,datetime,urllib.request,hashlib,ctypes as C,ctypes.wintypes as W,contextlib,zipfile
S=Path(__file__).resolve().parent;M=S.parent;REPO=M.parents[1]
sys.path.insert(0,str(M/'stage5a'))
from persistence_v2 import Lease,own_info,same_process,write
from retention7 import retire
PORT=33357;HTTP=8087

def read(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def digest(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def verify_engine():
 f=read(M/'stage6/final-acceptance/ENGINE_FREEZE.json')
 assert f['version']=='stage6-accepted-1ebb240ff4bc'
 for name,h in f['mainSourceSHA256'].items():assert digest(M/name)==h,name
 artifact=M/'target/matching-engine-0.1-SNAPSHOT.jar'
 if digest(artifact)!=f['jarSHA256']:
  # Maven ZIP timestamps/compression can differ after a clean source rebuild.
  # Require byte-identical CONTENT of every accepted entry, no additions or duplicates.
  content=read(M/'maintenance/ACCEPTED_JAR_CONTENT_SHA256.json')
  assert content['originalJarSHA256']==f['jarSHA256']
  with zipfile.ZipFile(artifact) as jar:
   entries=jar.infolist();assert len(entries)==len({e.filename for e in entries}),'Duplicate JAR entries'
   assert {e.filename:hashlib.sha256(jar.read(e)).hexdigest() for e in entries}==content['entries'],'Accepted JAR contents changed'
 return f
class Job:
 def __init__(self):
  k=C.WinDLL('kernel32',use_last_error=True);self.k=k
  class Basic(C.Structure):_fields_=[('processTime',C.c_longlong),('jobTime',C.c_longlong),('flags',W.DWORD),('minWS',C.c_size_t),('maxWS',C.c_size_t),('active',W.DWORD),('affinity',C.c_size_t),('priority',W.DWORD),('schedule',W.DWORD)]
  class IO(C.Structure):_fields_=[('v'+str(n),C.c_ulonglong) for n in range(6)]
  class Ext(C.Structure):_fields_=[('basic',Basic),('io',IO),('p',C.c_size_t),('j',C.c_size_t),('pp',C.c_size_t),('pj',C.c_size_t)]
  k.CreateJobObjectW.restype=W.HANDLE;k.SetInformationJobObject.argtypes=[W.HANDLE,C.c_int,C.c_void_p,W.DWORD];k.AssignProcessToJobObject.argtypes=[W.HANDLE,W.HANDLE];k.CloseHandle.argtypes=[W.HANDLE]
  self.h=k.CreateJobObjectW(None,None);v=Ext();v.basic.flags=0x2000
  if not self.h or not k.SetInformationJobObject(self.h,9,C.byref(v),C.sizeof(v)):raise OSError(C.get_last_error(),'Owned Job setup')
 def add(self,p):
  if not self.k.AssignProcessToJobObject(self.h,W.HANDLE(int(p._handle))):raise OSError(C.get_last_error(),'Owned Job assignment')
 def close(self):
  if self.h:self.k.CloseHandle(self.h);self.h=None

def run(root,verify):
 lease=Lease('stage7-demo');job=Job();server=app=None;state={'owner':own_info(),'run':str(root),'phase':'STARTING','dbPort':PORT,'httpPort':HTTP}
 def save(phase,**data):
  state.update(phase=phase,utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),**data);write(root/'STATE.json',state);write(S/'STATE.json',{'status':'STAGE7_IN_PROGRESS','phase':phase,'run':str(root),'owner':state['owner']})
 def start(args,name,env=None):
  log=(root/(name+'.log')).open('xb');p=subprocess.Popen(args,env=env,cwd=S,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW);log.close();job.add(p);return p
 try:
  verify_engine()
  for port in [PORT,HTTP]:
   with socket.socket() as sock:sock.bind(('127.0.0.1',port))
  write(root/'RETENTION.json',{'version':1,'presetOnlyUntilImported':True,'keepClosedPresetDatadirs':2})
  write(root/'RETIREMENT_RESULTS.json',retire(S/'runtime',same_process))
  mysql=(Path(os.environ['STAGE7_MYSQL_HOME'])/'bin/mysqld.exe') if os.environ.get('STAGE7_MYSQL_HOME') else M/'stage4cd/runs/batch-20261030T212745/runtime/mysql-8.4.8-winx64/bin/mysqld.exe';assert mysql.is_file(),'Set STAGE7_MYSQL_HOME to MySQL8.4.8 installation';assert '8.4.8' in subprocess.check_output([str(mysql),'--version'],text=True),'MySQL8.4.8 required'
  private=root/'private';private.mkdir();data=root/'mysql-data';data.mkdir()
  password=secrets.token_urlsafe(32);writerpass=secrets.token_urlsafe(32);rootpass=secrets.token_urlsafe(32);token=secrets.token_urlsafe(32)
  cnf=private/'server.cnf';cnf.write_text('[mysqld]\nbasedir='+mysql.parents[1].as_posix()+'\ndatadir='+data.as_posix()+'\nport='+str(PORT)+'\nbind-address=127.0.0.1\nmysqlx=OFF\nskip-name-resolve=ON\ninnodb-buffer-pool-size=134217728\nmax-connections=24\nlocal-infile=OFF\ncharacter-set-server=utf8mb4\ncollation-server=utf8mb4_unicode_ci\n',encoding='utf-8')
  init=start([str(mysql),'--defaults-file='+str(cnf),'--initialize-insecure'],'mysql-initialize');assert init.wait(timeout=180)==0
  bootstrap=private/'bootstrap.sql';bootstrap.write_text("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+rootpass+"';\nCREATE DATABASE matching_engine_exp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\nCREATE USER 'stage7_setup'@'127.0.0.1' IDENTIFIED BY '"+rootpass+"';\nGRANT ALL ON matching_engine_exp.* TO 'stage7_setup'@'127.0.0.1';\nGRANT SHUTDOWN ON *.* TO 'stage7_setup'@'127.0.0.1';\nCREATE USER 'stage7_reader'@'127.0.0.1' IDENTIFIED BY '"+password+"';\nCREATE USER 'stage7_writer'@'127.0.0.1' IDENTIFIED BY '"+writerpass+"';\nGRANT SELECT ON matching_engine_exp.* TO 'stage7_reader'@'127.0.0.1';\nGRANT SELECT,INSERT,UPDATE,DELETE ON matching_engine_exp.* TO 'stage7_writer'@'127.0.0.1';\n",encoding='utf-8')
  server=start([str(mysql),'--defaults-file='+str(cnf),'--console','--init-file='+str(bootstrap)],'mysql-server');save('INITIALIZING_MYSQL',mysqlLauncher=own_info(server.pid))
  end=time.monotonic()+120
  while True:
   if server.poll() is not None:raise RuntimeError('Owned MySQL stopped before startup')
   try:
    with socket.create_connection(('127.0.0.1',PORT),timeout=1):break
   except OSError:
    if time.monotonic()>end:raise TimeoutError('Owned MySQL startup')
    time.sleep(.5)
  clientcnf=private/'client.cnf';clientcnf.write_text('[client]\nuser=stage7_setup\npassword='+rootpass+'\nhost=127.0.0.1\nport='+str(PORT)+'\nprotocol=TCP\n',encoding='utf-8')
  schema=(M/'sql/001_schema.sql').read_bytes()+b'\nCREATE INDEX s5b_h01_party_name_contract ON contract_party(name,contract_id);\n'
  q=subprocess.run([str(mysql.with_name('mysql.exe')),'--defaults-file='+str(clientcnf),'--default-character-set=utf8mb4','--get-server-public-key','--host=127.0.0.1','--port='+str(PORT),'--user=stage7_setup','--protocol=TCP'],input=schema,capture_output=True,timeout=90);(root/'schema-load.log').write_bytes(q.stdout+q.stderr);assert q.returncode==0,'Owned schema initialization failed; see schema-load.log'
  uuid=next(x.split('=',1)[1].strip() for x in (data/'auto.cnf').read_text().splitlines() if x.strip().startswith('server-uuid='))
  write(root/'ownership.json',{'owner':own_info(),'datadir':str(data),'port':PORT,'serverUUID':uuid,'catalog':'matching_engine_exp','freshOwnedDirectory':True})
  env=os.environ.copy();env.update(MATCHING_DB_URL=f'jdbc:mysql://127.0.0.1:{PORT}/matching_engine_exp?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=15000',MATCHING_DB_USER='stage7_reader',MATCHING_DB_PASSWORD=password,STAGE7_WRITER_USER='stage7_writer',STAGE7_WRITER_PASSWORD=writerpass,STAGE7_RUN_DIR=str(root),STAGE7_DB_PORT=str(PORT),STAGE7_SERVER_UUID=uuid,STAGE7_LIFECYCLE_TOKEN=token,MAVEN_OPTS='-Xmx512m')
  if os.environ.get('STAGE7_LARGE_VERIFY')=='1':env['MAVEN_OPTS']='-Xmx768m'
  command=[str(Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe'),'-NoProfile','-ExecutionPolicy','Bypass','-File',str(S/'build-demo.ps1')]
  if verify:command+=['-Verify']
  save('BUILD_AND_TEST' if verify else 'BUILD');q=start(command,'build-test',env);assert q.wait(timeout=900)==0,'Build/test failed; raw log retained'
  app=start([str(Path(env['JAVA_HOME'])/'bin/java.exe'),'-Xms128m','-Xmx768m','-jar',str(S/'target/matching-engine-demo-0.1-SNAPSHOT.jar')],'application',env);save('WAITING_UI',app=own_info(app.pid))
  req=urllib.request.Request(f'http://127.0.0.1:{HTTP}/internal/health',headers={'X-Stage7-Token':token})
  end=time.monotonic()+600
  while True:
   if app.poll() is not None:raise RuntimeError('Application startup failed')
   try:
    with urllib.request.urlopen(req,timeout=3) as response:
     if response.read()==b'ready':break
   except Exception:
    if time.monotonic()>end:raise TimeoutError('UI startup (including Vaadin frontend)')
    time.sleep(1)
  save('READY',url=f'http://localhost:{HTTP}',lifetimeSeconds=7200)
  deadline=time.monotonic()+7200
  while time.monotonic()<deadline and not (root/'STOP_REQUESTED').exists():
   if app.poll() is not None or server.poll() is not None:break
   time.sleep(1)
  save('STOPPING')
 except BaseException as failure:
  save('BLOCKED',error=repr(failure));raise
 finally:
  if app is not None and app.poll() is None:
   try:
    req=urllib.request.Request(f'http://127.0.0.1:{HTTP}/internal/shutdown',data=b'',headers={'X-Stage7-Token':token})
    urllib.request.urlopen(req,timeout=5).close();app.wait(timeout=60)
   except Exception:pass
  graceful=False
  if server is not None and server.poll() is None:
   try:
    q=subprocess.run([str(mysql.with_name('mysqladmin.exe')),'--defaults-file='+str(clientcnf),'--get-server-public-key','shutdown'],capture_output=True,timeout=60);server.wait(timeout=30);graceful=q.returncode==0
   except Exception:pass
  job.close();lease.close()
  save('STOPPED' if state['phase']!='BLOCKED' else 'BLOCKED',ownedMySQLGracefulShutdown=graceful,ownedJobClosed=True,completedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())

def start(verify):
 with contextlib.closing(Lease('stage7-demo-start')):
  pointer=S/'runtime/ACTIVE.json'
  if pointer.exists():
   prior=read(pointer)
   if same_process(prior['owner']):print('Existing owned demo: '+prior['run']);return
  verify_engine();root=S/'runtime'/datetime.datetime.now().strftime('demo-%Y%m%dT%H%M%S');root.mkdir()
  with (root/'owner.log').open('xb') as log:
   p=subprocess.Popen([sys.executable,'-X','utf8','-B','-u',str(Path(__file__).resolve()),'_run',str(root),str(int(verify))],cwd=S,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_BREAKAWAY_FROM_JOB|subprocess.CREATE_NO_WINDOW)
  write(pointer,{'owner':own_info(p.pid),'run':str(root)});print(root)
if __name__=='__main__':
 action=sys.argv[1] if len(sys.argv)>1 else 'status'
 if action=='start':start('--verify' in sys.argv)
 elif action=='_run':run(Path(sys.argv[2]),bool(int(sys.argv[3])))
 elif action in ['status','stop']:
  a=read(S/'runtime/ACTIVE.json');alive=same_process(a['owner'])
  if action=='stop' and alive:(Path(a['run'])/'STOP_REQUESTED').write_text('User/QA requested owned shutdown\n')
  print(json.dumps({'ownerAlive':alive,'run':a['run'],'state':read(Path(a['run'])/'STATE.json') if (Path(a['run'])/'STATE.json').exists() else 'STARTING'},ensure_ascii=False))
 else:raise ValueError('start [--verify], status, stop only')
