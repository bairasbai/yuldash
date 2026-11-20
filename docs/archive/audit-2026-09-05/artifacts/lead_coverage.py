from pathlib import Path
import subprocess
names=subprocess.check_output(['git','ls-files','-z']).decode().split('\0')
rows=['# Lead coverage main3349472c. STATIC=read and reasoned; DATA=validated; RESOURCE=inventory only; DOC=context only.']
for n in filter(None,names):
 if n.split('/')[0] in ('android','backend','webapp','web','promo','yookassa-anketa'):continue
 p=Path(n)
 if p.suffix in ('.png','.mp4','.jar'):s='RESOURCE inventory; not executable source'
 elif p.suffix=='.md':s='DOC context/reference; not product code; not all claims reverified'
 elif n.startswith('.claude/skills/ui-ux-pro-max/data/'):s='DATA validated schema/JSON/index; advice not externally audited'
 elif n.startswith(('tools/','mobile/','experiments/','.github/','.claude/skills/ui-ux-pro-max/scripts/')):
  s='STATIC inspected source; runtime only where reported'
 else:s='PENDING auxiliary configuration'
 rows.append(s+'\t'+n)
Path('docs/audit-2026-09-05-lead-coverage.txt').write_text('\n'.join(rows)+'\n',encoding='utf-8')
