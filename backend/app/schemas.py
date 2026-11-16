import re
from typing import Annotated, Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, BeforeValidator, field_validator

def trim(value):
    return value.strip() if isinstance(value, str) else value

Text = Annotated[str, BeforeValidator(trim)]
Title = Annotated[Text, Field(min_length=3, max_length=100)]
Category = Literal['Завтраки','Супы','Основные блюда','Салаты','Десерты','Напитки']
Unit = Literal['г','кг','мл','л','шт.','ч. л.','ст. л.']

class Input(BaseModel):
    model_config = ConfigDict(extra='forbid', allow_inf_nan=False)

class Register(Input):
    login: Annotated[Text, Field(min_length=3,max_length=30,pattern=r'^[a-zA-Z0-9_]+$')]
    name: Annotated[Text, Field(min_length=2,max_length=50)]
    password: Annotated[str, Field(min_length=8,max_length=64)]

class Login(Input):
    login: Annotated[Text, Field(max_length=30)]
    password: Annotated[str, Field(max_length=64)]

class Ingredient(Input):
    name: Annotated[Text,Field(min_length=1,max_length=100)]
    amount: Annotated[float,Field(gt=0,le=10000)]
    unit: Unit

    @field_validator('amount', mode='before')
    @classmethod
    def decimal_comma(cls,v):
        if isinstance(v,bool): raise ValueError('Введите количество числом')
        return v.replace(',','.') if isinstance(v,str) else v

class RecipeInput(Input):
    title: Title
    description: Annotated[Text, Field(max_length=1000)] = ''
    category: Category
    minutes: Annotated[int, Field(strict=True,ge=1,le=1440)]
    servings: Annotated[int, Field(strict=True,ge=1,le=100)]
    ingredients: Annotated[list[Ingredient],Field(min_length=1,max_length=50)]
    steps: Annotated[list[Annotated[Text,Field(min_length=1,max_length=1000)]],Field(min_length=1,max_length=30)]
    image_id: UUID | None = None

class AttemptInput(RecipeInput):
    note: Annotated[Text, Field(min_length=1,max_length=2000)]
    parent_id: Annotated[int,Field(strict=True,gt=0)] | None = None

class GroupInput(Input):
    title: Title
    description: Annotated[Text,Field(max_length=1000)] = ''

class DishInput(Input):
    title: Title

class ReviewInput(Input):
    text: Annotated[Text,Field(min_length=1,max_length=2000)]

class RatingInput(Input):
    value: Annotated[int,Field(strict=True,ge=1,le=5)]

class ReactionInput(Input):
    value: Literal[-1,1]

class InviteInput(Input):
    login: Annotated[Text,Field(min_length=3,max_length=30)]

class InvitationAnswer(Input):
    accept: bool
