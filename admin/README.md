# Admin

React + Vite + Mantine. API origin is `VITE_API_URL` (see `.env.example`).

```
VITE_API_URL=http://127.0.0.1:8093   # default
VITE_API_URL=                        # same origin; Vite proxies to :8093
```

```bash
cp .env.example .env   # if you do not have .env yet
npm install && npm run dev
# http://127.0.0.1:5173/
```

The server is started from IntelliJ (not `./gradlew run`). Ktor must allow the Vite origin (`PANEL_CORS_ORIGINS`, default `http://127.0.0.1:5173` and `http://localhost:5173`).
