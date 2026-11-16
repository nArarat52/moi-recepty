"""Verify a consistent PostgreSQL backup in an isolated disposable database."""
import json
import subprocess
import time
from pathlib import Path
from uuid import uuid4
import psycopg
from psycopg import sql
from app.db import DATABASE_URL,connect
import io
from PIL import Image

def run(*args):
    return subprocess.run(['docker','compose',*args],cwd=Path(__file__).resolve().parents[2],check=True,capture_output=True,text=True)

def main():
    name='restore_check_'+uuid4().hex
    image_id=uuid4()
    image=io.BytesIO();Image.new('RGB',(16,16),'green').save(image,format='PNG')
    with connect() as db:
        db.execute("INSERT INTO images(id,owner_id,mime,data) VALUES(%s,(SELECT id FROM users ORDER BY id LIMIT 1),'image/png',%s)",(image_id,image.getvalue()))
    counts={}
    with connect() as db:
        for table in ['users','recipes','reviews','groups','memberships','dishes','attempts','images']:
            counts[table]=db.execute(sql.SQL('SELECT count(*) AS n FROM {}').format(sql.Identifier(table))).fetchone()['n']
    start=time.monotonic()
    run('exec','-T','db','pg_dump','-U','recipes','-d','recipes','-Fc','-f','/tmp/recipe-verification.dump')
    run('exec','-T','db','createdb','-U','recipes',name)
    try:
        run('exec','-T','db','pg_restore','-U','recipes','-d',name,'--no-owner','--single-transaction','/tmp/recipe-verification.dump')
        target=DATABASE_URL.rsplit('/',1)[0]+'/'+name
        with psycopg.connect(target) as db:
            restored={}
            for table in counts:
                restored[table]=db.execute(sql.SQL('SELECT count(*) FROM {}').format(sql.Identifier(table))).fetchone()[0]
            assert restored==counts,(counts,restored)
            # Byte-for-byte media verification is covered by pg_dump's BYTEA serialization;
            # compare a content digest in addition to row counts.
            digest=db.execute("SELECT md5(coalesce(string_agg(md5(data),'' ORDER BY id),'')) FROM images").fetchone()[0]
        with connect() as db:
            original=db.execute("SELECT md5(coalesce(string_agg(md5(data),'' ORDER BY id),'')) FROM images").fetchone()['md5']
        assert digest==original
        report={'status':'passed','duration_seconds':round(time.monotonic()-start,3),'counts':counts,'media_digest_matches':True,'includes_temporary_media_fixture':True}
        path=Path(__file__).resolve().parents[2]/'docs'/'backup-verification.json'
        path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(report,ensure_ascii=False))
    finally:
        run('exec','-T','db','dropdb','-U','recipes',name)
        with connect() as db: db.execute('DELETE FROM images WHERE id=%s',(image_id,))
if __name__=='__main__':main()
