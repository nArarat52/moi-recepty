"""Idempotent teaching data. python -m app.seed [--performance]"""
import sys
import json
from pathlib import Path
from datetime import datetime, timedelta, timezone
from psycopg.types.json import Jsonb
from .db import connect, initialize
from .main import hasher, insert_recipe, row
from .schemas import RecipeInput

NAMES=['Чечевичный суп','Овощной салат','Сырники','Паста с томатами','Яблочный пирог','Домашний лимонад',
       'Овсяная каша','Тыквенный суп','Рис с овощами','Греческий салат','Творожная запеканка','Ягодный морс',
       'Омлет с зеленью','Грибной суп','Запечённый картофель','Салат с фасолью','Банановые оладьи','Чай с имбирём',
       'Гречка с грибами','Томатный суп']
CATS=['Супы','Салаты','Завтраки','Основные блюда','Десерты','Напитки','Завтраки','Супы','Основные блюда','Салаты','Десерты','Напитки','Завтраки','Супы','Основные блюда','Салаты','Завтраки','Напитки','Основные блюда','Супы']

def seed(performance=False):
    initialize()
    with connect() as db:
        if row(db,"SELECT id FROM users WHERE login='anna'"):
            print('Демонстрационные данные уже существуют');return
        users=[]
        for login,name,days in [('anna','Анна',30),('mikhail','Михаил',-1),('olga','Ольга',None),('ivan','Иван',None)]:
            until=datetime.now(timezone.utc)+timedelta(days=days) if days is not None else None
            users.append(row(db,'INSERT INTO users(login,name,password_hash,subscription_until) VALUES(%s,%s,%s,%s) RETURNING id',(login,name,hasher.hash('Recipe2026!'),until))['id'])
        anna,mikhail,olga,ivan=users
        catalog=json.loads(Path(__file__).with_name('demo-recipes.json').read_text(encoding='utf-8'))
        for i in range(200 if performance else 20):
            j=i%20
            payload=dict(catalog[j]);payload['title']+=f' · {i//20+1}' if i>=20 else ''
            data=RecipeInput(**payload)
            rid=insert_recipe(db,users[i%3],data)['id']
            db.execute("UPDATE recipes SET created_at=now()-(%s * interval '1 minute') WHERE id=%s",(i,rid))
            if i<5:
                reviewer=users[(i+1)%3]
                db.execute('INSERT INTO ratings(user_id,recipe_id,value) VALUES(%s,%s,%s)',(reviewer,rid,4+i%2))
                review=row(db,'INSERT INTO reviews(author_id,recipe_id,text) VALUES(%s,%s,%s) RETURNING id',(reviewer,rid,'Вкусно! В следующий раз попробую добавить немного зелени.'))
                db.execute('INSERT INTO reactions(user_id,review_id,value) VALUES(%s,%s,1)',(ivan,review['id']))
        for owner,title,members in [(anna,'Семейная кухня',[anna,mikhail,olga]),(mikhail,'Тестовая лаборатория',[mikhail,anna])]:
            gid=row(db,'INSERT INTO groups(owner_id,title,description) VALUES(%s,%s,%s) RETURNING id',(owner,title,'Совместная работа над домашними рецептами'))['id']
            for uid in members: db.execute('INSERT INTO memberships(group_id,user_id) VALUES(%s,%s)',(gid,uid))
            did=row(db,'INSERT INTO dishes(group_id,title) VALUES(%s,%s) RETURNING id',(gid,'Идеальный чизкейк'))['id']
            parent=None
            for n in range(1,4):
                snap=RecipeInput(title='Идеальный чизкейк',description='Нежный домашний десерт',category='Десерты',minutes=50,servings=6,
                    ingredients=[{'name':'Творожный сыр','amount':500,'unit':'г'},{'name':'Сахар','amount':120-n*10,'unit':'г'}],steps=['Смешайте ингредиенты.','Выпекайте 50 минут при 150 °C.']).model_dump(mode='json')
                author=[ivan,mikhail,anna][n-1]
                parent=row(db,'INSERT INTO attempts(dish_id,author_id,number,parent_id,snapshot,note) VALUES(%s,%s,%s,%s,%s,%s) RETURNING id',
                    (did,author,n,parent,Jsonb(snap),['Базовый вариант. Слишком сладко.','Меньше сахара. Текстура стала лучше.','Нежная текстура, удачная температура.'][n-1]))['id']
        db.execute('INSERT INTO invitations(group_id,sender_id,recipient_id) SELECT id,owner_id,%s FROM groups WHERE owner_id=%s',(ivan,anna))
        print('Созданы 4 аккаунта, рецепты, 5 отзывов, 2 группы и история попыток.')

if __name__=='__main__': seed('--performance' in sys.argv)
