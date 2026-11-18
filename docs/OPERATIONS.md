# Установка и обслуживание

Требуются Docker Desktop с Linux-контейнерами и для сборки Android — Android Studio,
SDK 36, JDK 17+; проверка выполнена с JDK 21. Gradle Wrapper 9.4.1 включён в проект.

## Сервер

На Windows достаточно запустить `START.cmd` из корня проекта. Скрипт создаёт
локальный пароль и сохраняет его в `.tmp/demo-account-password.txt`.
Эта папка исключена из Git.

При ручном запуске из корня проекта сначала поднимите сервер, затем введите
свой пароль демо-аккаунтов, когда скрипт заполнения базы его запросит:

    docker compose up -d --build
    docker compose exec api python -m app.seed

При повторной подготовке используйте тот же пароль.
Постоянный том moi-recepty_recipe_db сохраняется после остановки.
Остановка без удаления данных:

    docker compose stop

По умолчанию порты доступны только на текущем компьютере:
API — 8000, PostgreSQL — 55432. Демонстрационный пароль БД находится в compose.yaml.
Можно задать POSTGRES_PASSWORD в локальном .env до первой инициализации тома.
Изменение переменной после создания базы само по себе не меняет пароль PostgreSQL.

DEMO_MODE=true включает активацию и завершение учебной подписки.
При DEMO_MODE=false эти действия возвращают запрет.
Во внешней среде настраивайте HTTPS на обратном прокси.
Для физического телефона в локальной демонстрации удобен adb reverse.

Проверка:

    Invoke-RestMethod http://127.0.0.1:8000/health
    docker compose logs --tail 100 api

Сервер не выводит токены и пароли в сообщения об ошибках, журнал HTTP-доступа отключён.

## Смена пароля в существующей демо-базе

Если демо-база создана предыдущей версией, выполните из корня проекта:

    .\START.cmd --update-demo-password

Введите действующий пароль демо-аккаунтов. Скрипт установит пароль из
`.tmp/demo-account-password.txt` и завершит старые сеансы этих аккаунтов.
Рецепты, группы и история сохранятся.

При ручном запуске используйте `docker compose exec api python -m app.seed --update-demo-password`
и введите новый и действующий пароли по запросу.

## Сборка Android

    .\scripts\Build-Android.ps1
    .\scripts\Build-Android.ps1 -Install

Версии библиотек перечислены в android/app/build.gradle.kts.
Debug APK допускает локальный HTTP; release-конфигурация запрещает открытый HTTP.
Отладочная подпись предназначена для учебной установки.
При импорте в Android Studio выберите папку android и Gradle JDK 17+.

## Тесты

При запущенной PostgreSQL:

    cd backend
    python -m venv .venv
    .\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
    .\.venv\Scripts\python.exe -m pytest -q

Тесты API создают отдельную схему acceptance_<uuid>, затем удаляют только её.
Рабочие таблицы приложения не сбрасываются.
Для теста каталога схема заполняется 200 рецептами.

UI-тесты используют демонстрационные аккаунты рабочего сервера:
они очищают локальную сессию приложения, меняют избранное и создают/удаляют тестовый отзыв.
Перед прогоном сервер должен содержать исходный набор демонстрационных данных.

    $demoPassword = (Get-Content .tmp/demo-account-password.txt -Raw).TrimEnd([char]13, [char]10)
    cd android
    adb reverse tcp:8000 tcp:8000
    .\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.base_url=http://127.0.0.1:8000 "-Pandroid.testInstrumentationRunnerArguments.demo_password=$demoPassword" lintDebug

Прямой ADB-туннель используется и для эмулятора: на проверенном компьютере
его NAT-адрес 10.0.2.2 периодически обрывал соединения. Параметр base_url
задаёт адрес только тестовой сессии. В обычном приложении адрес меняется
в Профиль → Подключение к серверу.

## Резервная копия

    .\scripts\Backup.ps1

Создаётся архив PostgreSQL custom format в backups. В него входят таблицы,
фотографии BYTEA и ограничения. На время pg_dump останавливать приложение не требуется.
Фотографии не нужно копировать отдельной файловой операцией.

Восстановление заменяет текущие данные. Сначала сохраните текущую копию:

    .\scripts\Restore.ps1 -Backup .\backups\recipes-YYYYMMDD-HHMMSS.dump -ConfirmReplace

API останавливается; pg_restore работает одной транзакцией; затем API запускается.
При ошибке восстановление откатывается. Если API не запущен автоматически,
выполните docker compose start api после устранения ошибки.
Резервные копии содержат данные аккаунтов и групп, храните их в ограниченном доступе.

Проверка восстановления в отдельную временную базу:

    cd backend
    .\.venv\Scripts\python.exe -m app.verify_backup

Скрипт сравнивает количества строк и хеши бинарных фото; рабочая база не заменяется.
Результат записывается в docs/backup-verification.json.
