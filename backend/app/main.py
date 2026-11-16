import hashlib
import io
import json
import os
import secrets
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from uuid import UUID, uuid4

import psycopg
from argon2 import PasswordHasher, Type
from argon2.exceptions import VerificationError
from fastapi import FastAPI, Depends, Header, Query, Request, UploadFile, File
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from PIL import Image, UnidentifiedImageError
from psycopg.types.json import Jsonb
from .db import get_db, initialize
from .schemas import *

hasher=PasswordHasher(time_cost=3,memory_cost=65536,parallelism=4,hash_len=32,salt_len=16,type=Type.ID)
DUMMY_HASH=hasher.hash('never-a-user-password')
EXPIRED='Подписка закончилась. Сохранённые материалы доступны для просмотра'
CATEGORIES=['Завтраки','Супы','Основные блюда','Салаты','Десерты','Напитки']

class Problem(Exception):
    def __init__(self,status,code,message,fields=None):
        self.status,self.code,self.message,self.fields=status,code,message,fields or {}

def fail(status,code,message):
    raise Problem(status,code,message)

def row(db,sql,args=()):
    return db.execute(sql,args).fetchone()

def need(item):
    if item is None: fail(404,'not_found','Запись не найдена')
    return item

def sha(value):
    return hashlib.sha256(value.encode()).hexdigest()

def current_user(authorization: str | None=Header(default=None),db=Depends(get_db)):
    if not authorization: return None
    if not authorization.startswith('Bearer '): fail(401,'unauthorized','Войдите в аккаунт')
    u=row(db,'SELECT u.*,coalesce(u.subscription_until>now(),false) AS subscription_active FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=%s AND s.expires_at>now()',(sha(authorization[7:]),))
    if not u: fail(401,'unauthorized','Сессия завершена. Войдите снова')
    return u

def auth(u=Depends(current_user)):
    if not u: fail(401,'unauthorized','Войдите в аккаунт')
    return u

def profile(u):
    until=u['subscription_until']
    return {k:u[k] for k in ['id','login','name','subscription_until']} | {'subscription_active':bool(u.get('subscription_active',False))}

def private_group(db,gid,uid,write=False,owner=False):
    g=need(row(db,'SELECT g.*,u.subscription_until,u.name AS owner_name, u.subscription_until>now() AS writable FROM groups g JOIN users u ON u.id=g.owner_id JOIN memberships m ON m.group_id=g.id AND m.user_id=%s WHERE g.id=%s FOR UPDATE OF g',(uid,gid)))
    if owner and g['owner_id']!=uid: fail(403,'forbidden','Только создатель управляет участниками')
    if write and not g['writable']: fail(403,'subscription_expired',EXPIRED)
    return g

def private_dish(db,did,uid,write=False):
    d=need(row(db,'SELECT * FROM dishes WHERE id=%s',(did,)))
    private_group(db,d['group_id'],uid,write)
    db.execute('SELECT id FROM dishes WHERE id=%s FOR UPDATE',(did,))
    return d

def check_image(db,image_id,uid,gid=None):
    if not image_id: return
    im=need(row(db,'SELECT * FROM images WHERE id=%s',(image_id,)))
    if gid is None:
        if im['group_id'] is not None or not (im['owner_id']==uid or row(db,'SELECT id FROM recipes WHERE image_id=%s AND author_id=%s',(image_id,uid))):
            fail(403,'forbidden','Фото недоступно')
    elif im['group_id']!=gid:
        fail(403,'forbidden','Фото должно принадлежать этой группе')

def operation(db,uid,key,scope,body,action):
    if not key or not 8<=len(key)<=128: fail(400,'idempotency_required','Требуется Idempotency-Key длиной 8–128 символов')
    fingerprint=sha(scope+json.dumps(jsonable_encoder(body),ensure_ascii=False,sort_keys=True))
    db.execute('SELECT pg_advisory_xact_lock(hashtextextended(%s,0))',(f'{uid}:{key}',))
    previous=row(db,'SELECT * FROM operations WHERE user_id=%s AND key=%s',(uid,key))
    if previous:
        if previous['fingerprint']!=fingerprint: fail(409,'idempotency_conflict','Ключ уже использован для других данных')
        return previous['response']
    result=jsonable_encoder(action())
    db.execute('INSERT INTO operations(user_id,key,fingerprint,response) VALUES(%s,%s,%s,%s)',(uid,key,fingerprint,Jsonb(result)))
    return result

@asynccontextmanager
async def lifespan(app):
    initialize()
    yield

app=FastAPI(title='Мои рецепты',version='1.0.0',lifespan=lifespan)

@app.exception_handler(Problem)
async def problem_handler(request,exc):
    return JSONResponse(status_code=exc.status,content={'code':exc.code,'message':exc.message,'fields':exc.fields})

@app.exception_handler(RequestValidationError)
async def validation_handler(request,exc):
    def message(e):
        kind=e['type'];ctx=e.get('ctx',{})
        if kind=='missing': return 'Заполните поле'
        if kind in ['string_too_short','too_short']: return 'Минимальное количество: '+str(ctx.get('min_length',1))
        if kind in ['string_too_long','too_long']: return 'Максимальное количество: '+str(ctx.get('max_length',''))
        if kind in ['int_type','int_parsing']: return 'Введите целое число'
        if kind in ['float_type','float_parsing','finite_number']: return 'Введите корректное число'
        if kind=='greater_than': return 'Количество должно быть больше '+str(ctx.get('gt',0))
        if kind in ['less_than_equal','greater_than_equal']: return 'Число выходит за допустимый диапазон'
        if kind=='literal_error': return 'Выберите значение из списка'
        if kind=='string_pattern_mismatch': return 'Используйте латинские буквы, цифры и знак подчёркивания'
        return 'Проверьте значение поля'
    fields={'.'.join(str(v) for v in e['loc'] if v!='body'):message(e) for e in exc.errors()}
    return JSONResponse(status_code=422,content={'code':'validation_error','message':'Проверьте заполнение полей','fields':fields})

@app.exception_handler(psycopg.errors.UniqueViolation)
async def conflict_handler(request,exc):
    login=exc.diag.constraint_name=='users_login_key'
    return JSONResponse(status_code=409,content={'code':'login_taken' if login else 'conflict','message':'Логин уже занят' if login else 'Такая запись уже существует','fields':{}})

@app.exception_handler(Exception)
async def unexpected_handler(request,exc):
    # Never send exception details, SQL parameters, passwords or tokens to the client.
    return JSONResponse(status_code=500,content={'code':'server_error','message':'Не удалось выполнить запрос. Повторите позже','fields':{}})

@app.get('/health')
def health(db=Depends(get_db)):
    db.execute('SELECT 1')
    return {'status':'ok','version':'1.0.0','demo_mode':os.getenv('DEMO_MODE','true').lower()=='true'}

@app.get('/categories')
def categories(): return CATEGORIES

@app.post('/auth/register',status_code=201)
def register(data:Register,db=Depends(get_db)):
    login=data.login.lower()
    if row(db,'SELECT id FROM users WHERE login=%s',(login,)): fail(409,'login_taken','Логин уже занят')
    u=row(db,'INSERT INTO users(login,name,password_hash) VALUES(%s,%s,%s) RETURNING *',(login,data.name,hasher.hash(data.password)))
    return open_session(db,u)

def open_session(db,u):
    token=secrets.token_urlsafe(32)
    db.execute('INSERT INTO sessions(token_hash,user_id) VALUES(%s,%s)',(sha(token),u['id']))
    return {'token':token,'user':profile(u)}

@app.post('/auth/login')
def login(data:Login,db=Depends(get_db)):
    u=row(db,'SELECT *,coalesce(subscription_until>now(),false) AS subscription_active FROM users WHERE login=%s',(data.login.lower(),))
    try: hasher.verify(u['password_hash'] if u else DUMMY_HASH,data.password)
    except VerificationError: fail(401,'invalid_credentials','Неверный логин или пароль')
    if not u: fail(401,'invalid_credentials','Неверный логин или пароль')
    return open_session(db,u)

@app.post('/auth/logout')
def logout(authorization:str=Header(),u=Depends(auth),db=Depends(get_db)):
    db.execute('DELETE FROM sessions WHERE token_hash=%s',(sha(authorization[7:]),))
    return {'ok':True}

@app.get('/me')
def me(u=Depends(auth)): return profile(u)

@app.post('/subscription/{action}')
def subscription(action:str,u=Depends(auth),db=Depends(get_db)):
    if os.getenv('DEMO_MODE','true').lower()!='true': fail(403,'demo_disabled','Демонстрационные действия отключены')
    if action not in ['activate','expire']: fail(404,'not_found','Действие не найдено')
    db.execute('SELECT id FROM users WHERE id=%s FOR UPDATE',(u['id'],))
    expr="greatest(coalesce(subscription_until,now()),now()) + interval '30 days'" if action=='activate' else 'now()'
    updated=row(db,f'UPDATE users SET subscription_until={expr} WHERE id=%s RETURNING *,subscription_until>now() AS subscription_active',(u['id'],))
    return profile(updated)

RECIPE_SELECT='''SELECT r.*,u.name AS author_name,
 (SELECT round(avg(value)::numeric,1)::float8 FROM ratings WHERE recipe_id=r.id) AS rating,
 (SELECT count(*) FROM ratings WHERE recipe_id=r.id) AS rating_count,
 EXISTS(SELECT 1 FROM favorites WHERE recipe_id=r.id AND user_id=%s) AS favorite,
 (SELECT value FROM ratings WHERE recipe_id=r.id AND user_id=%s) AS my_rating
 FROM recipes r JOIN users u ON u.id=r.author_id'''

@app.get('/recipes')
def list_recipes(q:str='',category:str|None=None,max_minutes:int|None=None,page:int=Query(1,ge=1),favorites:bool=False,mine:bool=False,u=Depends(current_user),db=Depends(get_db)):
    uid=u['id'] if u else 0
    if (favorites or mine) and not u: fail(401,'unauthorized','Войдите в аккаунт')
    if category and category not in CATEGORIES: fail(422,'validation_error','Неизвестная категория')
    if max_minutes not in [None,15,30,60]: fail(422,'validation_error','Выберите время 15, 30, 60 или любое')
    # position() treats % and _ as literal user text; all values remain parameterized.
    where=' WHERE position(lower(%s) in lower(r.title))>0'
    args=[q.strip()]
    for clause,value in [('r.category=%s',category),('r.minutes<=%s',max_minutes)]:
        if value is not None: where+=' AND '+clause;args.append(value)
    if mine: where+=' AND r.author_id=%s';args.append(uid)
    if favorites: where+=' AND EXISTS(SELECT 1 FROM favorites f WHERE f.recipe_id=r.id AND f.user_id=%s)';args.append(uid)
    total=row(db,'SELECT count(*) AS n FROM recipes r'+where,args)['n']
    items=db.execute(RECIPE_SELECT+where+' ORDER BY r.created_at DESC,r.id DESC LIMIT 20 OFFSET %s',[uid,uid]+args+[(page-1)*20]).fetchall()
    for item in items:
        item.pop('ingredients');item.pop('steps');item.pop('description')
    return {'items':items,'total':total,'page':page,'has_more':page*20<total}

@app.get('/recipes/{rid}')
def recipe(rid:int,u=Depends(current_user),db=Depends(get_db)):
    uid=u['id'] if u else 0
    return need(row(db,RECIPE_SELECT+' WHERE r.id=%s',(uid,uid,rid)))

def insert_recipe(db,uid,data):
    check_image(db,data.image_id,uid)
    return row(db,'''INSERT INTO recipes(author_id,title,description,category,minutes,servings,ingredients,steps,image_id)
      VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING id''',
      (uid,data.title,data.description,data.category,data.minutes,data.servings,Jsonb([v.model_dump() for v in data.ingredients]),Jsonb(data.steps),data.image_id))

@app.post('/recipes',status_code=201)
def create_recipe(data:RecipeInput,idempotency_key:str|None=Header(default=None),u=Depends(auth),db=Depends(get_db)):
    return operation(db,u['id'],idempotency_key,'recipe',data.model_dump(),lambda:insert_recipe(db,u['id'],data))

@app.put('/recipes/{rid}')
def update_recipe(rid:int,data:RecipeInput,u=Depends(auth),db=Depends(get_db)):
    r=need(row(db,'SELECT * FROM recipes WHERE id=%s FOR UPDATE',(rid,)))
    if r['author_id']!=u['id']: fail(403,'forbidden','Изменять рецепт может только его автор')
    check_image(db,data.image_id,u['id'])
    db.execute('''UPDATE recipes SET title=%s,description=%s,category=%s,minutes=%s,servings=%s,ingredients=%s,steps=%s,image_id=%s,updated_at=now() WHERE id=%s''',
      (data.title,data.description,data.category,data.minutes,data.servings,Jsonb([v.model_dump() for v in data.ingredients]),Jsonb(data.steps),data.image_id,rid))
    return {'id':rid}

@app.put('/recipes/{rid}/favorite')
def favorite(rid:int,u=Depends(auth),db=Depends(get_db)):
    need(row(db,'SELECT id FROM recipes WHERE id=%s',(rid,)))
    db.execute('INSERT INTO favorites(user_id,recipe_id) VALUES(%s,%s) ON CONFLICT DO NOTHING',(u['id'],rid))
    return {'favorite':True}

@app.delete('/recipes/{rid}/favorite')
def unfavorite(rid:int,u=Depends(auth),db=Depends(get_db)):
    db.execute('DELETE FROM favorites WHERE user_id=%s AND recipe_id=%s',(u['id'],rid))
    return {'favorite':False}

def foreign_recipe(db,rid,uid):
    r=need(row(db,'SELECT * FROM recipes WHERE id=%s',(rid,)))
    if r['author_id']==uid: fail(403,'own_recipe','Автор не может оценивать свой рецепт или писать к нему отзыв')
    return r

@app.put('/recipes/{rid}/rating')
def rate(rid:int,data:RatingInput,u=Depends(auth),db=Depends(get_db)):
    foreign_recipe(db,rid,u['id'])
    db.execute('INSERT INTO ratings(user_id,recipe_id,value) VALUES(%s,%s,%s) ON CONFLICT(user_id,recipe_id) DO UPDATE SET value=excluded.value',(u['id'],rid,data.value))
    return {'value':data.value}

@app.get('/recipes/{rid}/reviews')
def reviews(rid:int,u=Depends(current_user),db=Depends(get_db)):
    need(row(db,'SELECT id FROM recipes WHERE id=%s',(rid,)))
    uid=u['id'] if u else 0
    return db.execute('''SELECT r.*,u.name AS author_name,
      (SELECT count(*) FROM reactions WHERE review_id=r.id AND value=1) AS likes,
      (SELECT count(*) FROM reactions WHERE review_id=r.id AND value=-1) AS dislikes,
      (SELECT value FROM reactions WHERE review_id=r.id AND user_id=%s) AS my_reaction
      FROM reviews r JOIN users u ON u.id=r.author_id WHERE r.recipe_id=%s ORDER BY r.created_at DESC,r.id DESC''',(uid,rid)).fetchall()

@app.post('/recipes/{rid}/reviews',status_code=201)
def add_review(rid:int,data:ReviewInput,idempotency_key:str|None=Header(default=None),u=Depends(auth),db=Depends(get_db)):
    foreign_recipe(db,rid,u['id'])
    def create():
        if row(db,'SELECT id FROM reviews WHERE author_id=%s AND recipe_id=%s',(u['id'],rid)): fail(409,'review_exists','У вас уже есть отзыв. Его можно изменить')
        return row(db,'INSERT INTO reviews(author_id,recipe_id,text) VALUES(%s,%s,%s) RETURNING id',(u['id'],rid,data.text))
    return operation(db,u['id'],idempotency_key,f'review:{rid}',data.model_dump(),create)

def own_review(db,rid,uid):
    r=need(row(db,'SELECT * FROM reviews WHERE id=%s FOR UPDATE',(rid,)))
    if r['author_id']!=uid: fail(403,'forbidden','Изменять и удалять отзыв может только его автор')
    return r

@app.put('/reviews/{rid}')
def edit_review(rid:int,data:ReviewInput,u=Depends(auth),db=Depends(get_db)):
    own_review(db,rid,u['id']);db.execute('UPDATE reviews SET text=%s WHERE id=%s',(data.text,rid))
    return {'id':rid}

@app.delete('/reviews/{rid}')
def delete_review(rid:int,u=Depends(auth),db=Depends(get_db)):
    own_review(db,rid,u['id']);db.execute('DELETE FROM reviews WHERE id=%s',(rid,))
    return {'ok':True}

@app.post('/reviews/{rid}/reaction')
def react(rid:int,data:ReactionInput,u=Depends(auth),db=Depends(get_db)):
    r=need(row(db,'SELECT * FROM reviews WHERE id=%s FOR UPDATE',(rid,)))
    if r['author_id']==u['id']: fail(403,'own_review','Нельзя реагировать на свой отзыв')
    old=row(db,'SELECT value FROM reactions WHERE user_id=%s AND review_id=%s',(u['id'],rid))
    if old and old['value']==data.value:
        db.execute('DELETE FROM reactions WHERE user_id=%s AND review_id=%s',(u['id'],rid));return {'value':None}
    db.execute('INSERT INTO reactions(user_id,review_id,value) VALUES(%s,%s,%s) ON CONFLICT(user_id,review_id) DO UPDATE SET value=excluded.value',(u['id'],rid,data.value))
    return {'value':data.value}

@app.get('/groups')
def groups(u=Depends(auth),db=Depends(get_db)):
    return db.execute('''SELECT g.*,u.name AS owner_name,u.subscription_until>now() AS writable,
      (SELECT count(*) FROM memberships WHERE group_id=g.id) AS member_count,
      (SELECT count(*) FROM dishes WHERE group_id=g.id) AS dish_count
      FROM groups g JOIN memberships m ON m.group_id=g.id JOIN users u ON u.id=g.owner_id
      WHERE m.user_id=%s ORDER BY g.created_at DESC,g.id DESC''',(u['id'],)).fetchall()

@app.post('/groups',status_code=201)
def create_group(data:GroupInput,idempotency_key:str|None=Header(default=None),u=Depends(auth),db=Depends(get_db)):
    def create():
        me=row(db,'SELECT subscription_until>now() AS active FROM users WHERE id=%s FOR UPDATE',(u['id'],))
        if not me['active']: fail(403,'subscription_expired',EXPIRED)
        g=row(db,'INSERT INTO groups(owner_id,title,description) VALUES(%s,%s,%s) RETURNING id',(u['id'],data.title,data.description))
        db.execute('INSERT INTO memberships(group_id,user_id) VALUES(%s,%s)',(g['id'],u['id']))
        return g
    return operation(db,u['id'],idempotency_key,'group',data.model_dump(),create)

@app.get('/groups/{gid}')
def group(gid:int,u=Depends(auth),db=Depends(get_db)):
    g=private_group(db,gid,u['id'])
    g['members']=db.execute('SELECT u.id,u.login,u.name FROM users u JOIN memberships m ON m.user_id=u.id WHERE m.group_id=%s ORDER BY u.id',(gid,)).fetchall()
    g['dishes']=db.execute('''SELECT d.*,(SELECT count(*) FROM attempts WHERE dish_id=d.id) AS attempt_count FROM dishes d WHERE group_id=%s ORDER BY d.created_at DESC,d.id DESC''',(gid,)).fetchall()
    g['invitations']=db.execute('SELECT i.id,u.login,i.status FROM invitations i JOIN users u ON u.id=i.recipient_id WHERE i.group_id=%s ORDER BY i.id DESC',(gid,)).fetchall() if g['owner_id']==u['id'] else []
    return g

@app.post('/groups/{gid}/invitations',status_code=201)
def invite(gid:int,data:InviteInput,u=Depends(auth),db=Depends(get_db)):
    private_group(db,gid,u['id'],owner=True)
    recipient=row(db,'SELECT id FROM users WHERE login=%s',(data.login.lower(),))
    if not recipient: fail(404,'user_not_found','Пользователь не найден')
    if recipient['id']==u['id']: fail(409,'self_invitation','Нельзя пригласить себя')
    if row(db,'SELECT 1 FROM memberships WHERE group_id=%s AND user_id=%s',(gid,recipient['id'])): fail(409,'already_member','Пользователь уже в группе')
    if row(db,"SELECT id FROM invitations WHERE group_id=%s AND recipient_id=%s AND status='pending'",(gid,recipient['id'])): fail(409,'invitation_exists','Приглашение уже отправлено')
    return row(db,'INSERT INTO invitations(group_id,sender_id,recipient_id) VALUES(%s,%s,%s) RETURNING id',(gid,u['id'],recipient['id']))

@app.get('/invitations')
def invitations(u=Depends(auth),db=Depends(get_db)):
    return db.execute("SELECT i.*,g.title AS group_title,u.name AS sender_name FROM invitations i JOIN groups g ON g.id=i.group_id JOIN users u ON u.id=i.sender_id WHERE i.recipient_id=%s AND i.status='pending' ORDER BY i.id DESC",(u['id'],)).fetchall()

@app.post('/invitations/{iid}/answer')
def answer(iid:int,data:InvitationAnswer,u=Depends(auth),db=Depends(get_db)):
    inv=need(row(db,'SELECT * FROM invitations WHERE id=%s AND recipient_id=%s FOR UPDATE',(iid,u['id'])))
    if inv['status']!='pending': fail(409,'invitation_closed','Приглашение уже обработано')
    db.execute('SELECT id FROM groups WHERE id=%s FOR UPDATE',(inv['group_id'],))
    db.execute('UPDATE invitations SET status=%s WHERE id=%s',('accepted' if data.accept else 'declined',iid))
    if data.accept: db.execute('INSERT INTO memberships(group_id,user_id) VALUES(%s,%s) ON CONFLICT DO NOTHING',(inv['group_id'],u['id']))
    return {'ok':True,'group_id':inv['group_id']}

@app.delete('/groups/{gid}/members/{uid}')
def remove_member(gid:int,uid:int,u=Depends(auth),db=Depends(get_db)):
    private_group(db,gid,u['id'],owner=True)
    if uid==u['id']: fail(403,'owner_cannot_leave','Создатель не может исключить себя')
    db.execute('DELETE FROM memberships WHERE group_id=%s AND user_id=%s',(gid,uid))
    return {'ok':True}

@app.post('/groups/{gid}/leave')
def leave(gid:int,u=Depends(auth),db=Depends(get_db)):
    g=private_group(db,gid,u['id'])
    if g['owner_id']==u['id']: fail(403,'owner_cannot_leave','Создатель не может выйти из своей группы')
    db.execute('DELETE FROM memberships WHERE group_id=%s AND user_id=%s',(gid,u['id']))
    return {'ok':True}

@app.post('/groups/{gid}/dishes',status_code=201)
def create_dish(gid:int,data:DishInput,idempotency_key:str|None=Header(default=None),u=Depends(auth),db=Depends(get_db)):
    private_group(db,gid,u['id'],write=True)
    return operation(db,u['id'],idempotency_key,f'dish:{gid}',data.model_dump(),lambda:row(db,'INSERT INTO dishes(group_id,title) VALUES(%s,%s) RETURNING id',(gid,data.title)))

@app.get('/dishes/{did}')
def dish(did:int,u=Depends(auth),db=Depends(get_db)):
    d=private_dish(db,did,u['id'])
    d['writable']=private_group(db,d['group_id'],u['id'])['writable']
    d['attempts']=db.execute('SELECT a.id,a.number,a.parent_id,a.author_id,a.note,a.created_at,u.name AS author_name FROM attempts a JOIN users u ON u.id=a.author_id WHERE dish_id=%s ORDER BY number',(did,)).fetchall()
    return d

@app.get('/attempts/{aid}')
def attempt(aid:int,u=Depends(auth),db=Depends(get_db)):
    a=need(row(db,'SELECT a.*,u.name AS author_name,d.group_id FROM attempts a JOIN users u ON u.id=a.author_id JOIN dishes d ON d.id=a.dish_id WHERE a.id=%s',(aid,)))
    a['writable']=private_group(db,a['group_id'],u['id'])['writable']
    return a

@app.post('/dishes/{did}/attempts',status_code=201)
def create_attempt(did:int,data:AttemptInput,idempotency_key:str|None=Header(default=None),u=Depends(auth),db=Depends(get_db)):
    d=private_dish(db,did,u['id'],write=True)
    def create():
        if data.parent_id and not row(db,'SELECT id FROM attempts WHERE id=%s AND dish_id=%s',(data.parent_id,did)): fail(422,'invalid_parent','Исходная попытка должна относиться к тому же блюду')
        check_image(db,data.image_id,u['id'],d['group_id'])
        num=row(db,'SELECT coalesce(max(number),0)+1 AS n FROM attempts WHERE dish_id=%s',(did,))['n']
        snapshot=data.model_dump(mode='json',exclude={'note','parent_id'})
        return row(db,'INSERT INTO attempts(dish_id,author_id,number,parent_id,snapshot,note,image_id) VALUES(%s,%s,%s,%s,%s,%s,%s) RETURNING id,number',(did,u['id'],num,data.parent_id,Jsonb(snapshot),data.note,data.image_id))
    return operation(db,u['id'],idempotency_key,f'attempt:{did}',data.model_dump(),create)

@app.post('/images',status_code=201)
def upload(file:UploadFile=File(),group_id:int|None=None,u=Depends(auth),db=Depends(get_db)):
    if group_id: private_group(db,group_id,u['id'],write=True)
    raw=file.file.read(5242881)
    if len(raw)>5242880: fail(422,'image_size','Размер фото превышает 5 МиБ')
    try:
        with Image.open(io.BytesIO(raw)) as im:
            if im.format not in ['JPEG','PNG']: fail(422,'image_format','Выберите JPEG или PNG')
            if im.width*im.height>25000000: fail(422,'image_dimensions','Изображение слишком большое')
            mime='image/jpeg' if im.format=='JPEG' else 'image/png'
            im.verify()
    except (UnidentifiedImageError,OSError,Image.DecompressionBombError): fail(422,'image_format','Файл не является корректным JPEG или PNG')
    iid=uuid4()
    db.execute('INSERT INTO images(id,owner_id,group_id,mime,data) VALUES(%s,%s,%s,%s,%s)',(iid,u['id'],group_id,mime,raw))
    return {'id':iid}

@app.get('/images/{iid}')
def image(iid:UUID,u=Depends(current_user),db=Depends(get_db)):
    im=need(row(db,'SELECT * FROM images WHERE id=%s',(iid,)))
    if im['group_id']:
        if not u: fail(401,'unauthorized','Войдите в аккаунт')
        private_group(db,im['group_id'],u['id'])
    elif not row(db,'SELECT id FROM recipes WHERE image_id=%s',(iid,)) and not (u and im['owner_id']==u['id']):
        fail(404,'not_found','Фото не найдено')
    return Response(bytes(im['data']),media_type=im['mime'],headers={'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})
