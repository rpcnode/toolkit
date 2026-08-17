# rpcnode-client-watch

Демон на CDN-хосте: раз в час сравнивает `catalog.json` с GitHub latest, новые артефакты кладёт в `clients/_updates/<сеть>/<env>/<версия>/`, пишет в Telegram. **Каталог и живой пин не меняет.**

FetchClients → Настройки: URL вотчера (`http://<сервер>:8094`), токен бота, chat id → «Сохранить на сервер».

## Docker (как на сервере)

Порт **8094** публикуется на хост. Файрвол открываешь сам (агент ufw не трогает).

```bash
cp tools/client-watch/client-watch.env.example client-watch.env
# CLIENT_WATCH_TOKEN, GITHUB_TOKEN, при необходимости CLIENT_WATCH_CLIENTS_HOST

docker compose -f docker-compose.client-watch.yml up -d --build --pull never
```

Каталог с хоста: `CLIENT_WATCH_CLIENTS_HOST` (по умолчанию `./install/clients`). На CDN обычно путь до живого `install/clients`.

Проверка: `curl -fsS http://127.0.0.1:8094/healthz`

## Без Docker

```bash
cd tools/client-watch
go build -o rpcnode-client-watch .
```

```bash
export CLIENT_WATCH_CATALOG=/var/www/install/clients/catalog.json
export CLIENT_WATCH_CLIENTS=/var/www/install/clients
export CLIENT_WATCH_PUBLIC_BASE=https://rpcnode.dev/install
export CLIENT_WATCH_LISTEN=0.0.0.0:8094
export CLIENT_WATCH_TOKEN=длинный-секрет
export GITHUB_TOKEN=ghp_…

./rpcnode-client-watch
```

Состояние (telegram + уже отправленные теги): `clients/.watch/state.json` (0600).

## HTTP

| | |
|---|---|
| `GET /healthz` | без токена |
| `GET /api/v1/status` | telegram да/нет, last_check, seen |
| `PUT /api/v1/telegram` | `{"token":"…","chat":"…"}` — сохранить и тестовое сообщение |
| `POST /api/v1/check` | проверка сейчас |

`Authorization: Bearer $CLIENT_WATCH_TOKEN`, если токен задан.
