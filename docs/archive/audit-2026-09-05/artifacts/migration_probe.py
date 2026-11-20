from pathlib import Path
import os,sys
sys.stdout.reconfigure(encoding='utf-8')
root=Path.cwd(); sys.path.insert(0,str(root/'backend'))
db=root/'artifacts/audit-2026-09-05/migrations.sqlite'
assert not db.exists(), 'Use a new isolated DB for migration probe'
os.environ.update(ENV='dev',DATABASE_URL='sqlite:///'+str(db).replace('\\','/'),JWT_SECRET='audit-only-secret',SEED_DEMO='false',REDIS_URL='',TELEGRAM_BOT_TOKEN='',SENTRY_DSN='')
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
c=Config(str(root/'backend/alembic.ini'))
s=ScriptDirectory.from_config(c)
print('HEADS',s.get_heads(),flush=True)
print('REVISIONS',len(list(s.walk_revisions())),flush=True)
for action,target in [('upgrade','head'),('downgrade','base'),('upgrade','head')]:
 print('START',action,target,flush=True)
 getattr(command,action)(c,target)
 print('OK',action,target,flush=True)
from sqlalchemy import create_engine,inspect
from sqlmodel import SQLModel
from app import models
engine=create_engine(os.environ['DATABASE_URL']);ins=inspect(engine)
missing=[]
for name,table in SQLModel.metadata.tables.items():
 if not ins.has_table(name):missing.append(name);continue
 found={c['name'] for c in ins.get_columns(name)}
 missing.extend(name+'.'+c.name for c in table.columns if c.name not in found)
print('MISSING_MODEL_COLUMNS',missing)
