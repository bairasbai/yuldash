from pathlib import Path
import subprocess, csv, collections
root = Path.cwd()
files = subprocess.check_output(['git','ls-files','-z']).decode().split('\0')
rows=[]
for name in filter(None,files):
    b=(root/name).read_bytes()
    binary=b'\0' in b[:8192]
    group=name.split('/')[0]
    if group in ['backend','android']: owner=group
    elif group in ['webapp','web','promo','yookassa-anketa']: owner='web'
    else: owner='lead'
    rows.append(dict(path=name,owner=owner,bytes=len(b),lines='' if binary else len(b.splitlines()),kind='binary' if binary else 'text'))
with open('docs/audit-2026-09-05-inventory.csv','w',encoding='utf-8-sig',newline='') as f:
    w=csv.DictWriter(f,fieldnames=rows[0].keys());w.writeheader();w.writerows(rows)
print('Tracked files',len(rows))
print('Owners',dict(collections.Counter(r['owner'] for r in rows)))
print('Lead extensions',dict(collections.Counter(Path(r['path']).suffix for r in rows if r['owner']=='lead')))
