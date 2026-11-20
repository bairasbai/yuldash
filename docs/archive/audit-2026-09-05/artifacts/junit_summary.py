import xml.etree.ElementTree as E
from pathlib import Path
import json
p=Path('android/app/build/test-results/testDebugUnitTest')
rows=[]
for f in p.glob('TEST-*.xml'):
 r=E.parse(f).getroot(); rows.append({k:r.get(k) for k in ['name','tests','failures','errors','skipped']})
Path('artifacts/audit-2026-09-05/android-junit-summary.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
print('XML files',len(rows),'tests',sum(int(r['tests'] or 0) for r in rows),'failures',sum(int(r['failures'] or 0) for r in rows))
