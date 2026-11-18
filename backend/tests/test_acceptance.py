import io
import time
from concurrent.futures import ThreadPoolExecutor
from secrets import token_urlsafe
from uuid import uuid4
import psycopg
import pytest
from fastapi.testclient import TestClient
from PIL import Image
from app import db as dbmod
from app.main import app, hasher
from app.seed import seed

DEMO_PASSWORD = token_urlsafe(24)

def key(): return {'Idempotency-Key':str(uuid4())}

@pytest.fixture(scope='session',autouse=True)
def isolated_database():
    base=dbmod.DATABASE_URL
    schema='acceptance_'+uuid4().hex
    from psycopg import sql
    with psycopg.connect(base,autocommit=True) as db: db.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(schema)))
    dbmod.DATABASE_URL=base+('?options=-csearch_path%3D'+schema)
    with TestClient(app) as c:
        seed(True, demo_password=DEMO_PASSWORD)
        yield c
    dbmod.DATABASE_URL=base
    with psycopg.connect(base,autocommit=True) as db: db.execute(sql.SQL('DROP SCHEMA {} CASCADE').format(sql.Identifier(schema)))

@pytest.fixture
def c(isolated_database): return isolated_database

def token(c,login):
    r=c.post('/auth/login',json={'login':login,'password':DEMO_PASSWORD})
    assert r.status_code==200,r.text
    return {'Authorization':'Bearer '+r.json()['token']}

@pytest.fixture
def a(c): return token(c,'anna')
@pytest.fixture
def m(c): return token(c,'mikhail')
@pytest.fixture
def i(c): return token(c,'ivan')

def recipe_data():
    return dict(title='Проверочный рецепт',description='',category='Супы',minutes=25,servings=4,
      ingredients=[dict(name='Вода',amount='1,5',unit='л')],steps=['Доведите до кипения.'])

def group_ids(c,a):
    gs=c.get('/groups',headers=a).json()
    return next(g['id'] for g in gs if g['owner_id']==1),next(g['id'] for g in gs if g['owner_id']==2)

def test_catalog_search_pagination_performance(c):
    for _ in range(10):
        start=time.monotonic();r=c.get('/recipes');assert time.monotonic()-start<3
        assert r.status_code==200 and len(r.json()['items'])==20
    first=c.get('/recipes').json();second=c.get('/recipes?page=2').json()
    assert first['total']==200 and not ({r['id'] for r in first['items']}&{r['id'] for r in second['items']})
    results=c.get('/recipes',params={'q':'  СУП  ','category':'Супы','max_minutes':30}).json()['items']
    assert results and all('суп' in r['title'].lower() and r['minutes']<=30 and r['category']=='Супы' for r in results)
    assert c.get('/recipes?q=%25').json()['total']==0

def test_registration_password_hash_and_revocation(c):
    password = f' {token_urlsafe(24)} '
    data={'login':'New_User','name':' Новый пользователь ','password':password}
    r=c.post('/auth/register',json=data);assert r.status_code==201
    h={'Authorization':'Bearer '+r.json()['token']}
    assert c.post('/auth/register',json=data).status_code==409
    assert c.post('/auth/login',json={'login':'new_user','password':password.strip()}).status_code==401
    with dbmod.connect() as db:
        ph=db.execute("SELECT password_hash FROM users WHERE login='new_user'").fetchone()['password_hash']
        assert ph.startswith('$argon2id$v=19$m=65536,t=3,p=4$') and hasher.verify(ph,password)
    assert c.post('/auth/logout',headers=h).status_code==200
    assert c.get('/me',headers=h).status_code==401

def test_recipe_create_retry_edit_and_access(c,a,m):
    h=a|key();data=recipe_data()
    r=c.post('/recipes',headers=h,json=data);assert r.status_code==201,r.text
    rid=r.json()['id'];assert c.post('/recipes',headers=h,json=data).json()['id']==rid
    data['title']='Другое название'
    assert c.post('/recipes',headers=h,json=data).status_code==409
    assert c.put(f'/recipes/{rid}',headers=m,json=data).status_code==403
    assert c.put(f'/recipes/{rid}',headers=a,json=data).status_code==200
    assert c.get(f'/recipes/{rid}').json()['ingredients'][0]['amount']==1.5

def test_validation_and_atomicity(c,a):
    before=c.get('/recipes').json()['total']
    data=recipe_data();data['steps']=[]
    assert c.post('/recipes',headers=a|key(),json=data).status_code==422
    data=recipe_data();data['ingredients'][0]['amount']=0
    assert c.post('/recipes',headers=a|key(),json=data).status_code==422
    assert c.get('/recipes').json()['total']==before
    assert c.post('/recipes',headers=a,json=recipe_data()).status_code==400

def test_rating_review_independence_and_owner_rules(c,a,m,i):
    rid=c.post('/recipes',headers=a|key(),json=recipe_data()).json()['id']
    assert c.put(f'/recipes/{rid}/rating',headers=a,json={'value':5}).status_code==403
    assert c.post(f'/recipes/{rid}/reviews',headers=a|key(),json={'text':'Свой'}).status_code==403
    for value in [5,3]: assert c.put(f'/recipes/{rid}/rating',headers=m,json={'value':value}).status_code==200
    detail=c.get(f'/recipes/{rid}').json();assert detail['rating']==3 and detail['rating_count']==1
    h=m|key();r=c.post(f'/recipes/{rid}/reviews',headers=h,json={'text':'Хороший рецепт'});assert r.status_code==201
    review=r.json()['id'];assert c.post(f'/recipes/{rid}/reviews',headers=h,json={'text':'Хороший рецепт'}).json()['id']==review
    assert c.post(f'/recipes/{rid}/reviews',headers=m|key(),json={'text':'Второй'}).status_code==409
    assert c.post(f'/reviews/{review}/reaction',headers=m,json={'value':1}).status_code==403
    for value,expected in [(1,1),(-1,-1),(-1,None)]:
        assert c.post(f'/reviews/{review}/reaction',headers=i,json={'value':value}).json()['value']==expected
    assert c.put(f'/reviews/{review}',headers=i,json={'text':'Чужой'}).status_code==403
    c.post(f'/reviews/{review}/reaction',headers=i,json={'value':1})
    assert c.delete(f'/reviews/{review}',headers=m).status_code==200
    with dbmod.connect() as db: assert db.execute('SELECT count(*) AS n FROM reactions WHERE review_id=%s',(review,)).fetchone()['n']==0
    assert c.get(f'/recipes/{rid}').json()['rating']==3

def test_favorites_are_server_side_and_unique(c,a):
    for _ in range(2): assert c.put('/recipes/1/favorite',headers=a).status_code==200
    assert len(c.get('/recipes?favorites=true',headers=a).json()['items'])==1
    c.delete('/recipes/1/favorite',headers=a)
    assert c.get('/recipes?favorites=true',headers=a).json()['items']==[]
    assert c.get('/recipes?favorites=true').status_code==401

def test_privacy_including_excluded_author(c,a,i):
    gid,_=group_ids(c,a);g=c.get(f'/groups/{gid}',headers=a).json();did=g['dishes'][0]['id']
    d=c.get(f'/dishes/{did}',headers=a).json();aid=d['attempts'][0]['id']
    assert d['attempts'][0]['author_id']==4
    for path in [f'/groups/{gid}',f'/dishes/{did}',f'/attempts/{aid}']:
        assert c.get(path,headers=i).status_code==404
        assert c.get(path).status_code==401
    assert c.get('/groups',headers=i).json()==[]

def test_expired_group_readable_and_no_writes(c,a,m):
    _,gid=group_ids(c,a);g=c.get(f'/groups/{gid}',headers=a).json()
    assert not g['writable']
    assert c.post(f'/groups/{gid}/dishes',headers=a|key(),json={'title':'Запрещённое блюдо'}).status_code==403
    assert c.post('/groups',headers=m|key(),json={'title':'Новая группа'}).status_code==403
    did=g['dishes'][0]['id']
    data=recipe_data();data['note']='Результат'
    assert c.post(f'/dishes/{did}/attempts',headers=a|key(),json=data).status_code==403

def test_subscription_activate_extend_expire(c,i):
    first=c.post('/subscription/activate',headers=i).json()
    second=c.post('/subscription/activate',headers=i).json()
    from datetime import datetime
    assert (datetime.fromisoformat(second['subscription_until'])-datetime.fromisoformat(first['subscription_until'])).days==30
    assert c.post('/subscription/expire',headers=i).json()['subscription_active'] is False

def test_invitations_membership_and_immediate_revocation(c,a,i):
    gid,_=group_ids(c,a)
    invitation=c.get('/invitations',headers=i).json()[0]['id']
    assert c.post(f'/invitations/{invitation}/answer',headers=a,json={'accept':True}).status_code==404
    assert c.post(f'/invitations/{invitation}/answer',headers=i,json={'accept':True}).status_code==200
    assert c.get(f'/groups/{gid}',headers=i).status_code==200
    assert c.post(f'/groups/{gid}/leave',headers=a).status_code==403
    assert c.post(f'/groups/{gid}/invitations',headers=a,json={'login':'ivan'}).status_code==409
    assert c.post(f'/groups/{gid}/invitations',headers=i,json={'login':'olga'}).status_code==403
    assert c.delete(f'/groups/{gid}/members/4',headers=a).status_code==200
    assert c.get(f'/groups/{gid}',headers=i).status_code==404
    inv=c.post(f'/groups/{gid}/invitations',headers=a,json={'login':'ivan'}).json()['id']
    assert c.post(f'/invitations/{inv}/answer',headers=i,json={'accept':False}).status_code==200
    assert c.get(f'/groups/{gid}',headers=i).status_code==404

def test_immutable_attempts_snapshot_and_parent(c,a,m):
    gid,_=group_ids(c,a);did=c.get(f'/groups/{gid}',headers=a).json()['dishes'][0]['id']
    old=c.get(f'/dishes/{did}',headers=a).json()['attempts'][0]['id']
    original=c.get(f'/attempts/{old}',headers=a).json()
    data=recipe_data();data.update(note='Уменьшили воду',parent_id=old)
    h=m|key();new=c.post(f'/dishes/{did}/attempts',headers=h,json=data)
    assert new.status_code==201,new.text
    assert c.post(f'/dishes/{did}/attempts',headers=h,json=data).json()==new.json()
    saved=c.get(f"/attempts/{new.json()['id']}",headers=a).json()
    assert saved['author_id']==2 and saved['parent_id']==old and saved['number']==4
    assert c.get(f'/attempts/{old}',headers=a).json()==original
    assert c.put(f'/attempts/{old}',headers=a,json=data).status_code==405
    with pytest.raises(psycopg.Error):
        with dbmod.connect() as db: db.execute('UPDATE attempts SET note=%s WHERE id=%s',('overwrite',old))

def test_concurrent_attempt_numbering_and_retry(c,a):
    gid,_=group_ids(c,a);did=c.get(f'/groups/{gid}',headers=a).json()['dishes'][0]['id']
    data=recipe_data();data['note']='Параллельная попытка'
    def send(_): return c.post(f'/dishes/{did}/attempts',headers=a|key(),json=data).json()
    with ThreadPoolExecutor(max_workers=4) as pool: results=list(pool.map(send,range(4)))
    assert len({r['number'] for r in results})==4
    h=a|key()
    with ThreadPoolExecutor(max_workers=3) as pool: same=list(pool.map(lambda _:c.post('/recipes',headers=h,json=recipe_data()).json(),range(3)))
    assert len({r['id'] for r in same})==1

def test_parent_from_another_dish_rejected(c,a):
    gid,_=group_ids(c,a);did=c.get(f'/groups/{gid}',headers=a).json()['dishes'][0]['id']
    other=c.post(f'/groups/{gid}/dishes',headers=a|key(),json={'title':'Другое блюдо'}).json()['id']
    aid=c.get(f'/dishes/{did}',headers=a).json()['attempts'][0]['id']
    data=recipe_data();data.update(note='Неверная связь',parent_id=aid)
    assert c.post(f'/dishes/{other}/attempts',headers=a|key(),json=data).status_code==422
    assert c.get(f'/dishes/{other}',headers=a).json()['attempts']==[]

def test_photo_validation_and_protected_download(c,a,i):
    buf=io.BytesIO();Image.new('RGB',(10,10),'green').save(buf,format='PNG');raw=buf.getvalue()
    gid,_=group_ids(c,a)
    r=c.post(f'/images?group_id={gid}',headers=a,files={'file':('test.png',raw,'image/png')});assert r.status_code==201,r.text
    iid=r.json()['id']
    assert c.get(f'/images/{iid}',headers=a).content==raw
    assert c.get(f'/images/{iid}',headers=i).status_code==404
    assert c.get(f'/images/{iid}').status_code==401
    data=recipe_data();data['image_id']=iid
    assert c.post('/recipes',headers=a|key(),json=data).status_code==403
    assert c.post('/images',headers=a,files={'file':('bad.png',b'not a photo','image/png')}).status_code==422
    assert c.post('/images',headers=a,files={'file':('huge.png',b'0'*5242881,'image/png')}).status_code==422

def test_failure_after_insert_rolls_back_everything(c,a,monkeypatch):
    from app import main
    original=main.operation
    before=c.get('/recipes').json()['total']
    h=a|key()
    def interrupted(db,uid,key,scope,body,action):
        def create_then_fail():
            action()
            raise main.Problem(503,'injected_failure','Тестовый сбой до фиксации транзакции')
        return original(db,uid,key,scope,body,create_then_fail)
    monkeypatch.setattr(main,'operation',interrupted)
    assert c.post('/recipes',headers=h,json=recipe_data()).status_code==503
    assert c.get('/recipes').json()['total']==before
    monkeypatch.setattr(main,'operation',original)
    assert c.post('/recipes',headers=h,json=recipe_data()).status_code==201
    assert c.get('/recipes').json()['total']==before+1
