import os,sys,socket,ipaddress
os.environ['DATABASE_URL']='sqlite:///'
os.environ['ENV']='dev'
os.environ['SMS_PROVIDER']='mock'
os.environ['PAYMENTS_PROVIDER']='mock'
os.environ['SEED_DEMO']='false'
os.environ['SENTRY_DSN']=''
def local(address):
 if isinstance(address,str): return
 host=address[0]
 if host=='localhost': return
 try:
  if ipaddress.ip_address(host).is_loopback: return
 except ValueError: pass
 raise RuntimeError('Audit network isolation: non-loopback blocked')
connect=socket.socket.connect
connect_ex=socket.socket.connect_ex
getaddrinfo=socket.getaddrinfo
def guarded_connect(self,address): local(address);return connect(self,address)
def guarded_connect_ex(self,address):local(address);return connect_ex(self,address)
def guarded_getaddrinfo(host,port,*a,**k):
 if host is not None:local((host,port))
 return getaddrinfo(host,port,*a,**k)
socket.socket.connect=guarded_connect
socket.socket.connect_ex=guarded_connect_ex
socket.getaddrinfo=guarded_getaddrinfo
sys.path.insert(0,os.getcwd())
import pytest
raise SystemExit(pytest.main(sys.argv[1:]))
