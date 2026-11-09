"""Owned disposable native MySQL + Java in ONE Windows affinity job (mask15).
No shell commands, services, inherited database defaults, production DB or passwords in argv/logs.
"""
import ctypes as c,ctypes.wintypes as w,datetime,json,os,secrets,socket,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent;MODULE=ROOT.parents[2];CONTROL=ROOT/'control';CONTROL.mkdir(exist_ok=True)
K=c.WinDLL('kernel32',use_last_error=True);SIZE=c.c_size_t;HANDLE=w.HANDLE
class Basic(c.Structure):_fields_=[('processTime',c.c_longlong),('jobTime',c.c_longlong),('flags',w.DWORD),('minWS',SIZE),('maxWS',SIZE),('active',w.DWORD),('affinity',SIZE),('priority',w.DWORD),('schedule',w.DWORD)]
class IO(c.Structure):_fields_=[('v'+str(i),c.c_ulonglong) for i in range(6)]
class Extended(c.Structure):_fields_=[('basic',Basic),('io',IO),('processMemory',SIZE),('jobMemory',SIZE),('peakProcess',SIZE),('peakJob',SIZE)]
class SI(c.Structure):_fields_=[('cb',w.DWORD),('reserved',w.LPWSTR),('desktop',w.LPWSTR),('title',w.LPWSTR),('x',w.DWORD),('y',w.DWORD),('xs',w.DWORD),('ys',w.DWORD),('xc',w.DWORD),('yc',w.DWORD),('fill',w.DWORD),('flags',w.DWORD),('show',w.WORD),('reserved2size',w.WORD),('reserved2',c.c_void_p),('stdin',HANDLE),('stdout',HANDLE),('stderr',HANDLE)]
class PI(c.Structure):_fields_=[('process',HANDLE),('thread',HANDLE),('pid',w.DWORD),('tid',w.DWORD)]
class SA(c.Structure):_fields_=[('size',w.DWORD),('descriptor',c.c_void_p),('inherit',w.BOOL)]
class ThreadEntry(c.Structure):_fields_=[('size',w.DWORD),('use',w.DWORD),('tid',w.DWORD),('pid',w.DWORD),('priority',w.LONG),('delta',w.LONG),('flags',w.DWORD)]
class Memory(c.Structure):_fields_=[('cb',w.DWORD),('faults',w.DWORD)]+[(name,SIZE) for name in ('peakWorkingSet','workingSet','peakPagedPool','pagedPool','peakNonpagedPool','nonpagedPool','pagefile','peakPagefile','privateBytes')]
getmemory=c.WinDLL('psapi').GetProcessMemoryInfo;getmemory.restype=w.BOOL;getmemory.argtypes=[HANDLE,c.c_void_p,w.DWORD]
def memory(p):
    v=Memory();v.cb=c.sizeof(v);ok(getmemory(p.process,c.byref(v),c.sizeof(v)),'Memory counters');return {k:getattr(v,k) for k in ('workingSet','peakWorkingSet','privateBytes')}
class Group(c.Structure):_fields_=[('mask',SIZE),('group',w.WORD),('reserved',w.WORD*3)]
def bind(name,ret,args):
    f=getattr(K,name);f.restype=ret;f.argtypes=args;return f
createjob=bind('CreateJobObjectW',HANDLE,[c.c_void_p,w.LPCWSTR]);setjob=bind('SetInformationJobObject',w.BOOL,[HANDLE,c.c_int,c.c_void_p,w.DWORD]);queryjob=bind('QueryInformationJobObject',w.BOOL,[HANDLE,c.c_int,c.c_void_p,w.DWORD,c.c_void_p])
create=bind('CreateProcessW',w.BOOL,[w.LPCWSTR,w.LPWSTR,c.c_void_p,c.c_void_p,w.BOOL,w.DWORD,c.c_void_p,w.LPCWSTR,c.POINTER(SI),c.POINTER(PI)])
assign=bind('AssignProcessToJobObject',w.BOOL,[HANDLE,HANDLE]);setmask=bind('SetProcessAffinityMask',w.BOOL,[HANDLE,SIZE]);getmask=bind('GetProcessAffinityMask',w.BOOL,[HANDLE,c.POINTER(SIZE),c.POINTER(SIZE)])
resume=bind('ResumeThread',w.DWORD,[HANDLE]);wait=bind('WaitForSingleObject',w.DWORD,[HANDLE,w.DWORD]);exitcode=bind('GetExitCodeProcess',w.BOOL,[HANDLE,c.POINTER(w.DWORD)]);close=bind('CloseHandle',w.BOOL,[HANDLE]);terminate=bind('TerminateProcess',w.BOOL,[HANDLE,w.UINT])
createfile=bind('CreateFileW',HANDLE,[w.LPCWSTR,w.DWORD,w.DWORD,c.POINTER(SA),w.DWORD,w.DWORD,HANDLE])
snapshot=bind('CreateToolhelp32Snapshot',HANDLE,[w.DWORD,w.DWORD]);first=bind('Thread32First',w.BOOL,[HANDLE,c.POINTER(ThreadEntry)]);nextthread=bind('Thread32Next',w.BOOL,[HANDLE,c.POINTER(ThreadEntry)]);openthread=bind('OpenThread',HANDLE,[w.DWORD,w.BOOL,w.DWORD]);getgroup=bind('GetThreadGroupAffinity',w.BOOL,[HANDLE,c.POINTER(Group)])
times=bind('GetProcessTimes',w.BOOL,[HANDLE,c.POINTER(w.FILETIME),c.POINTER(w.FILETIME),c.POINTER(w.FILETIME),c.POINTER(w.FILETIME)])
def ok(x,label):
    if not x:raise OSError(c.get_last_error(),label)
def code(p):
    v=w.DWORD();ok(exitcode(p.process,c.byref(v)),'Exit code');return v.value
def cpu(p):
    values=[w.FILETIME() for _ in range(4)];ok(times(p.process,*[c.byref(x) for x in values]),'CPU times')
    return sum((x.dwHighDateTime<<32)+x.dwLowDateTime for x in values[2:])/1e7
def affinity(p):
    a,b=SIZE(),SIZE();ok(getmask(p.process,c.byref(a),c.byref(b)),'Process mask');assert a.value==15
    return a.value
def threads(p):
    found=[];ended=0;h=snapshot(4,0)
    if h==c.c_void_p(-1).value:raise OSError(c.get_last_error(),'Thread snapshot')
    e=ThreadEntry();e.size=c.sizeof(e)
    try:
        available=first(h,c.byref(e))
        while available:
            if e.pid==p.pid:
                t=openthread(0x40,False,e.tid)
                if not t:ended+=1
                else:
                    try:
                        g=Group()
                        if getgroup(t,c.byref(g)):
                            assert g.group==0 and g.mask==15,(p.pid,e.tid,g.group,g.mask)
                            found.append({'tid':e.tid,'mask':g.mask,'group':g.group})
                        else:ended+=1
                    finally:close(t)
            available=nextthread(h,c.byref(e))
    finally:close(h)
    return {'pid':p.pid,'processMask':affinity(p),'threads':found,'threadsTerminatedDuringRead':ended,'cpuSeconds':cpu(p),'memory':memory(p)}
job=createjob(None,None);ok(job,'Create job');limits=Extended();limits.basic.flags=0x2010;limits.basic.affinity=15
ok(setjob(job,9,c.byref(limits),c.sizeof(limits)),'Set job affinity+kill-on-close')
actual=Extended();ok(queryjob(job,9,c.byref(actual),c.sizeof(actual),None),'Query job');assert actual.basic.affinity==15 and actual.basic.flags&0x2010==0x2010
(ROOT/'audit/native-job.json').write_text(json.dumps({'affinity':actual.basic.affinity,'limitFlags':actual.basic.flags,'sharedBudget':'ONE shared set logical0..3 / physical core0+1, both SMT siblings','mechanism':'Windows affinity job, suspended children assigned before any child execution','exclusiveReservation':False},indent=2))
children=[]
def start(exe,args,label):
    assert Path(exe).is_file();pi=PI();si=SI();si.cb=c.sizeof(si);si.flags=0x100
    sa=SA(c.sizeof(SA),None,True);log=createfile(str(ROOT/'audit'/(label+'.console.log')),0x40000000,3,c.byref(sa),2,0x80,None);ok(log,'Create log')
    nul=createfile('NUL',0x80000000,3,c.byref(sa),3,0x80,None);ok(nul,'Open NUL');si.stdin=nul;si.stdout=log;si.stderr=log
    try:
        ok(create(str(exe),c.create_unicode_buffer(subprocess.list2cmdline([str(exe),*map(str,args)])),None,None,True,0x08000004,None,str(ROOT),c.byref(si),c.byref(pi)),'Create suspended process')
        try:
            ok(assign(job,pi.process),'Assign shared CPU job');ok(setmask(pi.process,15),'Set process mask');affinity(pi)
            if resume(pi.thread)==0xffffffff:raise OSError(c.get_last_error(),'Resume')
        except BaseException:terminate(pi.process,125);raise
        finally:close(pi.thread)
    finally:close(log);close(nul)
    children.append(pi);return pi
def finish(p,timeout=120):
    end=time.monotonic()+timeout
    while wait(p.process,1000)==258:
        if time.monotonic()>end:raise TimeoutError('Owned process timeout')
    status=code(p)
    if status:raise RuntimeError('Owned child failed, pid='+str(p.pid)+' exit='+str(status)+'; see console log')
def write(path,data):path.write_text(json.dumps(data,indent=2),encoding='utf-8')
try:
    instance=ROOT/'runtime'/('native-'+datetime.datetime.now().strftime('%Y%m%dT%H%M%S'));instance.mkdir(exist_ok=False)
    mysql=Path('C:\\Users\\saveliy\\Documents\\git repo\\alpha-matcher\\experiments\\matching-engine\\stage4cd\\runs\\batch-20261030T212745\\runtime\\mysql-8.4.8-winx64\\bin\\mysqld.exe');datadir=instance/'mysql-data';private=instance/'private'
    if datadir.exists():raise RuntimeError('Refuse existing data directory')
    datadir.mkdir();private.mkdir();port=33347
    with socket.socket() as test:test.bind(('127.0.0.1',port))
    password=secrets.token_urlsafe(32);rootpass=secrets.token_urlsafe(32)
    config=private/'server.cnf'
    config.write_text('[mysqld]\nbasedir='+str(mysql.parents[1]).replace('\\','/')+'\ndatadir='+str(datadir).replace('\\','/')+'\nport='+str(port)+'\nbind-address=127.0.0.1\nmysqlx=OFF\nskip-name-resolve=ON\ninnodb-buffer-pool-size=134217728\nmax-connections=32\nlocal-infile=OFF\ncharacter-set-server=utf8mb4\ncollation-server=utf8mb4_unicode_ci\nlog-error='+str(ROOT/'audit/mysql-server.err').replace('\\','/')+'\n',encoding='utf-8')
    init=start(mysql,['--defaults-file='+str(config),'--initialize-insecure'],'mysql-initialize');finish(init)
    bootstrap=private/'bootstrap.sql';bootstrap.write_text("ALTER USER 'root'@'localhost' IDENTIFIED BY '"+rootpass+"';\nCREATE DATABASE matching_engine_exp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\nCREATE USER 'matcher'@'127.0.0.1' IDENTIFIED BY '"+password+"';\nCREATE USER 'v4_reader'@'127.0.0.1' IDENTIFIED BY '"+password+"';\nGRANT ALL ON matching_engine_exp.* TO 'matcher'@'127.0.0.1';\nGRANT SELECT ON matching_engine_exp.* TO 'v4_reader'@'127.0.0.1';\nGRANT SELECT ON performance_schema.* TO 'matcher'@'127.0.0.1';\nGRANT SELECT ON performance_schema.* TO 'v4_reader'@'127.0.0.1';\nGRANT PROCESS ON *.* TO 'matcher'@'127.0.0.1';\n",encoding='utf-8')
    server=start(mysql,['--defaults-file='+str(config),'--init-file='+str(bootstrap)],'mysql-server')
    deadline=time.monotonic()+90
    while True:
        if code(server)!=259:raise RuntimeError('MySQL exited before ready')
        try:
            with socket.create_connection(('127.0.0.1',port),timeout=1):break
        except OSError:
            if time.monotonic()>deadline:raise TimeoutError('MySQL ready timeout')
            time.sleep(1)
    write(CONTROL/'READY.json',{'serverPid':server.pid,'port':port,'datadir':str(datadir),'affinity':threads(server),'jobMask':15})
    print('Owned MySQL ready: port33347; both processes constrained by ONE job mask15',flush=True)
    connector=Path(os.environ['USERPROFILE'])/'.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar'
    assert connector.is_file();java=Path(os.environ.get('JAVA_HOME',r'C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot'))/'bin/java.exe'
    flags=['--enable-native-access=ALL-UNNAMED','-Xms256m','-Xmx1024m','-XX:+UseG1GC','-XX:ParallelGCThreads=4','-XX:ConcGCThreads=2','-XX:CICompilerCount=2']
    seen=set();stop=time.monotonic()+4*3600
    while time.monotonic()<stop:
        if code(server)!=259:raise RuntimeError('Owned MySQL died')
        requests=sorted(CONTROL.glob('request-*.json'))
        for request in requests:
            if request.name in seen or '.response.' in request.name:continue
            seen.add(request.name);r=json.loads(request.read_text());action=r['action'];response={'action':action,'startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
            if action=='stop':write(CONTROL/(request.stem+'.response.json'),{'status':'STOPPING'});raise SystemExit(0)
            if action not in ('load','tests','evaluate','performance','known123','snapshot'):raise ValueError('Unsupported fixed action')
            os.environ['MATCHING_DB_URL']='jdbc:mysql://127.0.0.1:33347/matching_engine_exp?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8'
            os.environ['MATCHING_DB_USER']='matcher';os.environ['MATCHING_DB_PASSWORD']=password
            os.environ['MATCHING_DB_DATA_DIR']=str(datadir)
            os.environ['STAGE4F_ROOT']=str(ROOT)
            os.environ['STAGE4F_REQUEST']=request.stem
            os.environ['MATCHING_SERVER_PID']=str(server.pid)
            classes=ROOT/r.get('classes','current-classes')
            if action=='tests':
                os.environ['STAGE4F_TESTS']=r.get('tests','')
                powershell=Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe'
                client=start(powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'tools/run-tests.ps1')],request.stem)
            else:
                arguments=[str(ROOT)]
                cls='ru.urfu.alphamatcher.experiment.Stage4FDriver'
                if action=='load':arguments+=['load',r['source'],r['output']]
                elif action=='evaluate':
                    os.environ['MATCHING_DB_USER']='v4_reader'
                    arguments+=['evaluate',r['dataset'],r['output'],r.get('kind','known')]
                elif action=='snapshot':arguments+=['snapshot',r['output']]
                elif action=='performance':cls='ru.urfu.alphamatcher.experiment.Stage4FPerformance';arguments+=[r['profile'],r['version']]
                elif action=='known123':
                    cls='ru.urfu.alphamatcher.experiment.FinalKnownRegression'
                    arguments=[str(ROOT/r['output']),str(ROOT)]
                    flagsExtra=['-Dbatch.output='+str(ROOT/r['output'])]
                else:raise ValueError('Unsupported action')
                client=start(java,[*flags,*(flagsExtra if action=='known123' else []),'-cp',str(classes)+';'+str(connector),cls,*arguments],request.stem)
            write(ROOT/'audit'/(request.stem+'.start-affinity.json'),{'javaOrLauncher':threads(client),'mysql':threads(server)})
            samples=[];prior=(time.monotonic(),cpu(server)+cpu(client))
            try:
                while wait(client.process,1000)==258:
                    now=time.monotonic();total=cpu(server)+cpu(client);rate=(total-prior[1])/(now-prior[0]);prior=(now,total)
                    samples.append({'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mysql':threads(server),'java':threads(client),'combinedCpuCoresInterval':rate,'budgetLogicalCores':4})
                    if len(samples)>3600:raise TimeoutError('Evaluation >1h')
                finish(client,2);response['status']='PASS';response['exitCode']=0
            except BaseException as error:
                response.update(status='FAIL',error=repr(error));write(CONTROL/(request.stem+'.response.json'),response);write(ROOT/'audit'/(request.stem+'.cpu.json'),samples);continue
            write(ROOT/'audit'/(request.stem+'.cpu.json'),samples);response['finishedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat();response['samples']=len(samples)
            response['finalServerAffinity']=threads(server);write(CONTROL/(request.stem+'.response.json'),response)
            print(request.stem+' '+action+' '+response['status']+'; samples='+str(len(samples)),flush=True)
        time.sleep(.5)
    raise TimeoutError('Controller4h safety stop')
except SystemExit:raise
except BaseException as error:
    write(ROOT/'audit/controller-failure.json',{'error':repr(error)});raise
finally:
    close(job) # kill-on-close applies ONLY to our job's children; no service/user process is touched.
    for child in children:close(child.process)


