import importlib.util, io, contextlib
from pathlib import Path
s=importlib.util.spec_from_file_location('contrast', Path('tools/contrast.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
t=m.parse(); del t['CanonText']; m.parse=lambda:t
out=io.StringIO()
with contextlib.redirect_stdout(out): code=m.main()
print('Missing CanonText; exit=',code)
print('\n'.join(x for x in out.getvalue().splitlines() if 'нет токена' in x or 'проходят' in x))
