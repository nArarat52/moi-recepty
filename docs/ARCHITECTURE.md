# Архитектура

## Клиент

Android 10+ / API 29. Kotlin, Jetpack Compose, Material 3.
MainActivity — оболочка и тема; AppState — состояние, навигация и сетевые действия;
PublicScreens — каталог и аккаунт; CommunityScreens — обратная связь и группы;
RecipeForm — редактор рецепта и попытки; Api — HTTP и хранилище сессии.

Компоненты соответствуют Material 3 в Figma: OutlinedTextField, Button,
NavigationBar и карточки. Фон #F7F9F7, акцент #356B4A, Roboto.
Элементы располагаются потоково, формы и списки прокручиваются.

HTTP-запросы выполняются вне главного потока, общий callTimeout — 15 секунд.
Автоматический повтор изменяющих запросов отключён: повтор инициирует пользователь.
Ключи создания сохраняются между попытками одного и того же содержимого формы.
ViewModel сохраняет открытую форму при повороте и сетевой ошибке; офлайн-синхронизации нет.

Токен зашифрован AES-GCM ключом Android Keystore. Пароли на диск не записываются.
Приватные данные и изображения не сохраняются в постоянный клиентский кэш.
Выход очищает состояние и отзывает токен на сервере.

## API и данные

Python 3.12 / FastAPI / psycopg / PostgreSQL 16. Все запросы SQL параметризованы.
Транзакция охватывает запись данных и результат операции идемпотентности.
Ключ создания привязан к пользователю, виду операции и отпечатку тела запроса.
Повтор с тем же телом возвращает прежний результат; другое тело с тем же ключом отклоняется.
Транзакционная advisory-блокировка сериализует конкурентные повторы.
Строка группы и блюда блокируются при выдаче номера попытки, исключая одинаковые номера.

Основные таблицы: users, sessions, recipes, favorites, ratings, reviews, reactions,
groups, memberships, invitations, dishes, attempts, images, operations.
Ингредиенты и шаги хранятся валидированными массивами JSONB с сохранением порядка.
Попытка содержит полный JSONB-снимок, автора, номер, дату и необязательный parent_id.
API не предоставляет обновление или удаление попытки; триггер БД запрещает такие изменения.

Пароли: Argon2id, m=65536 КиБ, t=3, p=4, соль 16 байт, результат 32 байта.
Токены сессий случайные; в БД хранится SHA-256 токена, срок действия — 30 дней.
Проверка членства выполняется на каждом приватном запросе, включая изображения.
Закрытая запись при отсутствии членства возвращает 404 без раскрытия содержания.
Для создания материалов дополнительно проверяется срок подписки владельца группы.
Подписка использует время PostgreSQL, включая точную границу окончания.

Фотографии проверяются по фактическому формату JPEG/PNG и лимиту 5 МиБ.
Защита от чрезмерных размеров: до 25 млн пикселей.
Бинарные данные хранятся в PostgreSQL BYTEA, поэтому pg_dump даёт согласованную копию
данных и фото в одной транзакции. Закрытые фото не обслуживаются через публичный static URL.

## API

Интерактивное описание: http://127.0.0.1:8000/docs
Машиночитаемая схема: http://127.0.0.1:8000/openapi.json

- /auth/register, /auth/login, /auth/logout, /me
- /recipes, /recipes/{id}, /recipes/{id}/favorite, /recipes/{id}/rating
- /recipes/{id}/reviews, /reviews/{id}, /reviews/{id}/reaction
- /groups, /groups/{id}, /groups/{id}/invitations, /groups/{id}/members/{user}
- /groups/{id}/leave, /invitations, /invitations/{id}/answer
- /groups/{id}/dishes, /dishes/{id}, /dishes/{id}/attempts, /attempts/{id}
- /subscription/activate, /subscription/expire, /images, /images/{id}

Ошибки имеют поля code, message и fields.
Создание рецепта, группы, блюда, отзыва и попытки требует Idempotency-Key.
Фотографии передаются multipart/form-data; остальные формы — JSON UTF-8.
Даты — ISO 8601 с часовым поясом; клиент отображает местное время.

## Основания выбора инструментов

[Совместимость AGP 9.2, Gradle 9.4.1 и Kotlin 2.2.10](https://developer.android.com/build/releases/agp-9-2-0-release-notes).
[Документация безопасности FastAPI](https://fastapi.tiangolo.com/tutorial/security/oauth2-jwt/).
[Транзакционные блокировки PostgreSQL 16](https://www.postgresql.org/docs/16/explicit-locking.html).
[Политика восстановления соединения OkHttp 4.12](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt).
