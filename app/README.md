# toolkit-kotlin

Kotlin + **Ktor**. Clean / hexagonal. Vertical slices, then layers inside each slice.

Go `../toolkit` is knowledge only: do not edit, do not run.

```
auth            login / logout / session / htpasswd
setup           first-run admin
settings        CDN origin + GitHub token
servers         host registry
clients         client pins / downloads
panel/<slice>/presentation/http   Ktor routes
panel/presentation/http   host, CORS, /healthz
catalog            LookupNetworkUseCase (id, never port)
datadir/domain     joinData
networks/<id>/domain   shipped Chain facts
```

## Layout

```
rpcnode.toolkit.auth.domain.model                      Username, Session
rpcnode.toolkit.auth.domain.repository                 CredentialStore, SessionStore
rpcnode.toolkit.auth.application.login                 LoginUseCase
rpcnode.toolkit.auth.infrastructure.persistence
rpcnode.toolkit.panel.auth.presentation.http         /api/auth/*

rpcnode.toolkit.setup.application.create               CreateAdminUseCase
rpcnode.toolkit.panel.setup.presentation.http        /api/setup

rpcnode.toolkit.settings.domain.model                InstallOrigin, Channel
rpcnode.toolkit.settings.application.get|save
rpcnode.toolkit.panel.settings.presentation.http     /api/settings

rpcnode.toolkit.shared.infrastructure.persistence    ToolkitDatabase (toolkit.db, Flyway + Exposed)
rpcnode.toolkit.panel.presentation.http              Application, CORS, healthz
rpcnode.toolkit.catalog.*                            Chain, NetworkId, lookup
rpcnode.toolkit.wiring                               composition root
```

## Run

Operator starts the server from IntelliJ IDEA. Agents do **not** `./gradlew run` or restart `java`.

```bash
# http://127.0.0.1:8094/api/auth/status
# React UI: ../admin  VITE_API_URL → this process
```

`PANEL_LISTEN`, `PANEL_PORT` (default **8094**), `TOOLKIT_DB`, `PANEL_HTPASSWD`, `PANEL_SESSIONS`, `PANEL_CORS_ORIGINS` (unset = Vite/admin localhost; blank = allow any Origin so admin `:8093` can call server `:8094`). Admin UI is `:8093`.

Admin first-run: enter the server origin, then the password. `VITE_API_URL` in `../admin/.env` is optional.
