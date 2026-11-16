import os
from pathlib import Path
import psycopg
from psycopg.rows import dict_row

DATABASE_URL = os.getenv('DATABASE_URL', 'postgresql://recipes:recipes_local_demo@127.0.0.1:55432/recipes')

def connect():
    return psycopg.connect(DATABASE_URL, row_factory=dict_row, connect_timeout=5)

def get_db():
    with connect() as db:
        yield db

def initialize():
    with connect() as db:
        db.execute(Path(__file__).with_name('schema.sql').read_text(encoding='utf-8'))
