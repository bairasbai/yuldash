from pathlib import Path
import sys, tokenize, io
p=Path(sys.argv[1]); start=int(sys.argv[2]) if len(sys.argv)>2 else 1; end=int(sys.argv[3]) if len(sys.argv)>3 else 10**9
sys.stdout.reconfigure(encoding="utf-8")
src=p.read_text(encoding='utf-8-sig'); ignored=set()
if p.suffix=='.py':
 try:
  tokens=list(tokenize.generate_tokens(io.StringIO(src).readline))
  previous=tokenize.INDENT
  for t in tokens:
   if (t.type==tokenize.COMMENT and not t.line[:t.start[1]].strip()) or (t.type==tokenize.STRING and previous in (tokenize.INDENT, tokenize.NEWLINE, tokenize.ENCODING)):
    ignored.update(range(t.start[0],t.end[0]+1))
   if t.type not in (tokenize.NL, tokenize.COMMENT): previous=t.type
 except tokenize.TokenError: pass
block=False
for n,line in enumerate(src.splitlines(),1):
 if n<start or n>end or n in ignored or not line.strip(): continue
 if p.suffix in ('.kt','.kts','.ts','.tsx','.js','.dart','.swift'):
  s=line.strip()
  if block:
   if '*/' in s: block=False
   continue
  if s.startswith('/*'):
   block='*/' not in s; continue
  if s.startswith('//'): continue
 print(f'{n}: {line}')

