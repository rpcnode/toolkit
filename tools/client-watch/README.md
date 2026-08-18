# rpcnode-client-watch

Раз в час: GitHub **или HEAD CDN-бинаря** vs `catalog.json`. Версия — с GitHub/HEAD, папка на диске для этого не нужна. Если есть что качать и `_updates/<сеть>/<env>/<версия>/` ещё нет — кладёт туда, старые папки не удаляет. Нет linux-файлов (nitro/docker) — только версия + Telegram, пустую папку не создаёт. Каталог не меняет.

Cron (один проход, без HTTP):

```bash
# один раз собрать на сервере:
#   cd /path/to/toolkit/tools/client-watch && go build -o rpcnode-client-watch .

0 * * * * set -a && . /path/to/toolkit/client-watch.env && set +a && /path/to/toolkit/tools/client-watch/rpcnode-client-watch -check -catalog /path/to/toolkit/install/clients/catalog.json -clients /path/to/toolkit/install/clients >> /var/log/rpcnode-client-watch.log 2>&1
```

Hyperliquid (`hl-visor`) без тегов: пин = `Last-Modified` + короткий ETag (`2026-06-20-a955094a`).

Telegram: `TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT` в `client-watch.env` (cron так и берёт). Либо FetchClients → Настройки → «Сохранить на сервер» (state.json). Env важнее state.

## Docker

Токен GitHub — в `client-watch.env` (уже в корне, gitignored):

https://github.com/settings/tokens/new?description=rpcnode-client-watch

Scopes не нужны. Вставь `GITHUB_TOKEN=ghp_…` и перезапусти контейнер. Без токена — 60 req/час и 403.

```bash
docker compose --env-file client-watch.env -f docker-compose.client-watch.yml up -d --build --pull never
```

Проверка версий (токен из `client-watch.env` иначе 403):

```bash
docker compose --env-file client-watch.env -f docker-compose.client-watch.yml run --rm --no-deps --entrypoint /usr/local/bin/rpcnode-client-watch client-watch -once
```

Сеть — `network_mode: bridge` (docker0), новую не создаём. Если снова «address pools … subnetted» — `docker network prune`.

Порт **8094**. Файрвол открываешь сам.

FetchClients → Настройки: `http://<сервер>:8094` → токен бота + chat → **Сохранить на сервер**.

Каталог с хоста: `CLIENT_WATCH_CLIENTS_HOST` (по умолчанию `./install/clients`).

`curl -fsS http://127.0.0.1:8094/healthz`
