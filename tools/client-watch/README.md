# rpcnode-client-watch

Раз в час: GitHub vs `catalog.json`. Новое — файлы в `clients/_updates/`, в Telegram строка `сеть — вышла новая версия X`. Каталог не меняет.

Telegram (бот + chat) задаётся из FetchClients по API и лежит в Docker volume `client-watch-state` — **rebuild контейнера его не стирает**. Не делай `docker compose down -v`.

## Docker

Стартовый env уже в compose. Копировать файлы не нужно.

```bash
docker compose -f docker-compose.client-watch.yml up -d --build --pull never
```

Порт **8094**. Файрвол открываешь сам.

FetchClients → Настройки: `http://<сервер>:8094` → токен бота + chat → **Сохранить на сервер**.

Каталог с хоста: `CLIENT_WATCH_CLIENTS_HOST` (по умолчанию `./install/clients`).

`curl -fsS http://127.0.0.1:8094/healthz`
