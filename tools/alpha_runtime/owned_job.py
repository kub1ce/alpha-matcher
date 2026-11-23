"""Owned kill-on-close Job for bounded QA runtimes; extracted from the verified lifecycle primitive."""
import ctypes as C, ctypes.wintypes as W

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