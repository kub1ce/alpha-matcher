"""Windows-only research lifecycle primitives. Never targets processes by name."""
from pathlib import Path
import ctypes as C,ctypes.wintypes as W,json,os,time,datetime,hashlib
MODULE=Path(__file__).resolve().parents[1];REPO=MODULE.parents[1]
K=C.WinDLL('kernel32',use_last_error=True)
def bind(name,result,args):
 f=getattr(K,name);f.restype=result;f.argtypes=args;return f
close=bind('CloseHandle',W.BOOL,[W.HANDLE]);wait=bind('WaitForSingleObject',W.DWORD,[W.HANDLE,W.DWORD]);openprocess=bind('OpenProcess',W.HANDLE,[W.DWORD,W.BOOL,W.DWORD]);times=bind('GetProcessTimes',W.BOOL,[W.HANDLE,*[C.POINTER(W.FILETIME)]*4]);injob=bind('IsProcessInJob',W.BOOL,[W.HANDLE,W.HANDLE,C.POINTER(W.BOOL)]);mutex=bind('CreateMutexW',W.HANDLE,[C.c_void_p,W.BOOL,W.LPCWSTR]);release=bind('ReleaseMutex',W.BOOL,[W.HANDLE])
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def write(path,value):
 path=Path(path);tmp=path.with_name(path.name+'.tmp');tmp.write_text(json.dumps(value,indent=2,ensure_ascii=True),encoding='utf-8');os.replace(tmp,path)
def sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as f:
  for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
 return h.hexdigest()
def own_info(pid=None):
 pid=pid or os.getpid();h=openprocess(0x1000|0x100000,False,pid)
 if not h:return None
 try:
  fs=[W.FILETIME() for _ in range(4)]
  if not times(h,*[C.byref(x) for x in fs]):return None
  size=W.DWORD(32768);buf=C.create_unicode_buffer(size.value);f=bind('QueryFullProcessImageNameW',W.BOOL,[W.HANDLE,W.DWORD,W.LPWSTR,C.POINTER(W.DWORD)]);ok=f(h,0,buf,C.byref(size));flag=W.BOOL();injob(h,None,C.byref(flag))
  return {'pid':pid,'creationFileTime':(fs[0].dwHighDateTime<<32)|fs[0].dwLowDateTime,'image':buf.value if ok else None,'insideAnyJob':bool(flag.value),'alive':wait(h,0)==258}
 finally:close(h)
def same_process(record):
 now=own_info(record['pid']);return bool(now and now['alive'] and now['creationFileTime']==record['creationFileTime'] and now['image']==record['image'])
class Lease:
 def __init__(self,suffix='coordinator'):
  name='Local\\AlphaMatcher.Stage5A.'+hashlib.sha256(str(MODULE).casefold().encode()).hexdigest()[:20]+'.'+suffix
  self.h=mutex(None,False,name)
  if not self.h:raise OSError(C.get_last_error(),'Create owned mutex')
  status=wait(self.h,0)
  if status not in [0,128]:close(self.h);self.h=None;raise RuntimeError('ALREADY_RUNNING: workspace '+suffix+' mutex is owned')
 def close(self):
  if self.h:release(self.h);close(self.h);self.h=None
class Checkpoint:
 def __init__(self,root):self.root=Path(root);self.started=time.monotonic();self.owner=own_info();self.phase='STARTING'
 def save(self,phase=None,**extra):
  if phase:self.phase=phase
  record={'batchId':self.root.name,'phase':self.phase,'utc':utc(),'owner':self.owner,**extra};write(self.root/'STATE.json',record);write(self.root/'control/HEARTBEAT.json',record)
  with (self.root/'state-transitions.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(record)+'\n')
  text=f"# Stage5A continuation\n\nBatch: `{self.root}`\nPhase: **{self.phase}**\nOwner: PID{self.owner['pid']}, creationFileTime{self.owner['creationFileTime']}\nUTC: {record['utc']}\n\nVerified historical MIXED100k:367.0620371s,272.433ops/s,100000 equal Decisions,50000 correct SAFE,0 wrong SAFE,DB unchanged.\nInvalid historical MIXED100k:368.6772736s (selected-domain interference); excluded from SLA/median.\nIncomplete attempts and failed persistent-v1 preparation remain untouched.\n\nStatus: `python experiments/matching-engine/stage5a/stage5a_v2.py status`\nResume safely: `python experiments/matching-engine/stage5a/stage5a_v2.py start` (returns existing owner instead of duplicating).\nStop own stand: `python experiments/matching-engine/stage5a/stage5a_v2.py stop`\n\nDetails: {json.dumps(extra,ensure_ascii=False)}\n"
  (self.root/'CONTINUATION.md').write_text(text,encoding='utf-8')
