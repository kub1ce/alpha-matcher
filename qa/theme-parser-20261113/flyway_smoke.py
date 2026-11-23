"""Fresh, explicitly owned, bounded Flyway smoke. Never connects to an existing database."""
from pathlib import Path
import json,os,secrets,shutil,socket,subprocess,sys,time
REPO=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(REPO/'tools/alpha_runtime'))
from owned_job import Job
sys.path.insert(0,str(REPO/'experiments/matching-engine/stage5a'))
from persistence_v2 import write,own_info

def main():
 assert shutil.disk_usage(REPO).free>=40*1024**3
 home=REPO/'tools/runtime-dependencies/mysql-8.4.8-winx64';port=33369
 with socket.socket() as probe:probe.bind(('127.0.0.1',port))
 root=Path(__file__).parent/'runtime'/('alpha-matcher-flyway-'+str(time.time_ns()));root.mkdir()
 private=root/'private';private.mkdir();data=root/'data';data.mkdir()
 job=Job();server=None;password=secrets.token_urlsafe(32)
 cnf=private/'server.cnf';cnf.write_text('[mysqld]\nbasedir='+home.as_posix()+'\ndatadir='+data.resolve().as_posix()+'\nport='+str(port)+'\nbind-address=127.0.0.1\nmysqlx=OFF\nskip-log-bin\ninnodb-buffer-pool-size=134217728\ninnodb-redo-log-capacity=104857600\nmax-connections=12\n',encoding='utf-8')
 try:
  with (root/'initialize.log').open('xb') as log:
   init=subprocess.Popen([str(home/'bin/mysqld.exe'),'--defaults-file='+str(cnf),'--initialize-insecure'],stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW);job.add(init);assert init.wait(timeout=120)==0
  bootstrap=private/'bootstrap.sql'
  bootstrap.write_text("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+password+"'; CREATE DATABASE alpha_matcher CHARACTER SET utf8mb4; CREATE DATABASE unrelated_probe; CREATE TABLE unrelated_probe.marker(value VARCHAR(20)); INSERT INTO unrelated_probe.marker VALUES ('keep'); CREATE USER 'migration_smoke_admin'@'127.0.0.1' IDENTIFIED BY '"+password+"'; GRANT ALL ON *.* TO 'migration_smoke_admin'@'127.0.0.1' WITH GRANT OPTION; CREATE USER 'migration_smoke'@'127.0.0.1' IDENTIFIED BY '"+password+"'; GRANT ALL ON alpha_matcher.* TO 'migration_smoke'@'127.0.0.1';",encoding='utf-8')
  client=private/'client.cnf';client.write_text('[client]\nhost=127.0.0.1\nport='+str(port)+'\nuser=migration_smoke_admin\npassword='+password+'\n',encoding='utf-8')
  with (root/'server.log').open('xb') as log:
   server=subprocess.Popen([str(home/'bin/mysqld.exe'),'--defaults-file='+str(cnf),'--init-file='+str(bootstrap),'--console'],stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
  job.add(server);write(root/'OWNER.json',{'owner':own_info(),'server':own_info(server.pid),'data':str(data.resolve()),'port':port})
  deadline=time.monotonic()+60
  while True:
   try:
    with socket.create_connection(('127.0.0.1',port),timeout=1):break
   except OSError:
    assert server.poll() is None and time.monotonic()<deadline;time.sleep(.5)
  uuid=next(s.split('=',1)[1].strip() for s in (data/'auto.cnf').read_text().splitlines() if s.startswith('server-uuid='))
  env=os.environ.copy();env.update(ALPHA_MATCHER_MIGRATION_SMOKE_URL=f'jdbc:mysql://127.0.0.1:{port}/alpha_matcher?useSSL=false&allowPublicKeyRetrieval=true',ALPHA_MATCHER_MIGRATION_SMOKE_PASSWORD=password,ALPHA_MATCHER_MIGRATION_SMOKE_ROOT_PASSWORD=password,ALPHA_MATCHER_MIGRATION_SMOKE_DATADIR=str(data.resolve()),ALPHA_MATCHER_MIGRATION_SMOKE_UUID=uuid,MAVEN_OPTS='-Xmx512m -XX:ActiveProcessorCount=4')
  with (root/'maven.log').open('xb') as log:
   code=subprocess.run([str(REPO/'mvnw.cmd'),'-q','-Dtest=FlywayMigrationSmokeTest','test'],cwd=REPO,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=180).returncode
  import xml.etree.ElementTree as ET
  suite=ET.parse(REPO/'target/surefire-reports/TEST-ru.urfu.alphamatcher.config.FlywayMigrationSmokeTest.xml').getroot()
  counts={k:int(suite.attrib.get(k,0)) for k in ['tests','failures','errors','skipped']}
  write(root/'RESULT.json',{'status':'PASS' if code==0 and counts==dict(tests=1,failures=0,errors=0,skipped=0) else 'FAIL','exitCode':code,**counts,'serverUUID':uuid,'datadir':str(data.resolve()),'productionTouched':False})
  print(root);assert code==0 and counts==dict(tests=1,failures=0,errors=0,skipped=0)
 finally:
  if server and server.poll() is None:
   subprocess.run([str(home/'bin/mysqladmin.exe'),'--defaults-file='+str(client),'--get-server-public-key','shutdown'],capture_output=True,timeout=60);server.wait(timeout=60)
  job.close()
if __name__=='__main__':main()
