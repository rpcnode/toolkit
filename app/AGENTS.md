# toolkit-kotlin — Ktor server

Kotlin rewrite. The Go checkout `../toolkit` is **knowledge only**.

- Do **not** edit Go files.
- Do **not** run Go `./dev`, agents, or panel from that repo.

Architecture: [`ARCHITECTURE.md`](ARCHITECTURE.md)

Repo: `app/` is this backend. `../admin/` is the React admin.

The panel is sliced by feature. `rpcnode.toolkit.panel` is HTTP only; domain/application/infrastructure sit beside the slice like `clients` (`rpcnode.toolkit.settings.domain`, not `rpcnode.toolkit.panel.settings.domain`):

```
auth            login, logout, session, htpasswd
setup           first-run admin
settings        CDN origin, GitHub token
servers         host registry
panel.<slice>.presentation.http   Ktor routes
panel.presentation.http   host, CORS, healthz, config
shared.infrastructure.persistence   toolkit.db
```

Identity is `NetworkId`, never a port number.

**Do not start Java.** The operator runs the server from IntelliJ IDEA. ❌ `./gradlew run` ❌ kill/restart `java` on `:8093`.
