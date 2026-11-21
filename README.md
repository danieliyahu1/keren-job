# keren-job

[![build-and-push](https://github.com/danieliyahu1/keren-job/actions/workflows/build-and-push.yaml/badge.svg)](https://github.com/danieliyahu1/keren-job/actions/workflows/build-and-push.yaml)

Describe a job in plain language; an agent searches HireMe and returns matches.

One pod, two containers: a Spring Boot UI/API and a headless OpenCode agent
runtime. The app never searches a job board itself — it asks the agent, and the
agent calls HireMe's public JSON API. The image carries no browser.

## Architecture

```mermaid
flowchart TB
    user(["Job seeker<br/>browser"])
    cf["Cloudflare Tunnel<br/>kerenjob.danieliyahu.com"]
    vault["OCI Vault"]

    subgraph k8s["k3s node (arm64) · namespace kerenjob"]
        svc["Service kerenjob<br/>ClusterIP :8080"]

        subgraph pod["Pod kerenjob · 1 replica · Recreate"]
            app["app container<br/>Spring Boot 4 · Java 21<br/>:8080 UI/API · :9090 actuator"]
            oc["opencode container<br/>opencode serve :4096<br/>HireMe skill"]
            app -->|"REST + Basic auth<br/>127.0.0.1:4096"| oc
        end

        ext["ExternalSecret<br/>kerenjob-secrets"]
        vm["VictoriaMetrics"]
        grafana["Grafana<br/>kerenjob / Overview"]

        ext -.->|"env vars"| pod
        app -.->|"metrics :9090"| vm
        vm --> grafana
    end

    model["OpenCode model<br/>longcat-2.5-free"]
    hireme["HireMe API<br/>hiremetech.com/api/jobs"]

    user -->|"HTTPS"| cf
    cf -->|"HTTP :8080"| svc
    svc --> app
    vault -.->|"2 secrets"| ext
    oc -->|"model calls"| model
    oc -->|"public JSON API"| hireme
```

| Container | Image | Role |
|---|---|---|
| `app` | `ghcr.io/danieliyahu1/keren-job` | Spring Boot 4 UI/API on `:8080`; operational endpoints on `:9090` |
| `opencode` | `ghcr.io/danieliyahu1/keren-job-opencode` | `opencode serve` on `:4096`: the agent runtime with the `hiremetech-jobs` skill |

The containers share a pod so the app can reach OpenCode on loopback
(`http://127.0.0.1:4096`); the port is never exposed through a Service. Both
images are pinned to a commit SHA **and** a registry digest.

## How a search flows

```mermaid
sequenceDiagram
    autonumber
    actor U as Job seeker
    participant UI as Browser (htmx)
    participant APP as Spring Boot app
    participant OC as OpenCode V2 server
    participant AG as Agent + HireMe skill
    participant HM as HireMe API

    U->>UI: Describe the job
    UI->>APP: POST /api/search
    APP->>APP: OpenCodeService builds the hiremetech-jobs prompt
    APP->>OC: POST /api/session
    OC-->>APP: session id
    APP->>OC: POST /api/session/{id}/prompt
    OC->>AG: run the agent loop in the background
    AG->>HM: search jobs (public JSON API)
    HM-->>AG: job listings
    AG-->>OC: assistant message

    loop poll every 500 ms until finished
        APP->>OC: POST /api/experimental/session/{id}/wait
        APP->>OC: GET newest assistant message
    end

    OC-->>APP: final assistant text
    APP->>OC: DELETE /api/session/{id}
    APP-->>UI: fragments/response (HTML)
    UI-->>U: up to 3 matches with links
```

`ChatClient` is wired to `OpenCodeChatModel` (the `@Primary` `ChatModel`), which
speaks the OpenCode V2 REST API directly. A call creates a fresh session, admits
the prompt, waits for the newest assistant message to finish, reads its text,
and deletes the session in a `finally` block.

## Project layout

```
src/main/java/com/akatsuki/keren_job/
├── controller/         HomeController, SearchController, GlobalExceptionHandler
├── service/            OpenCodeService — prompt building and ChatClient calls
├── opencode/           OpenCodeChatModel — OpenCode V2 REST client
├── config/             ChatClientConfig, OpenCodeProcessManager (local auto-start)
└── dto/                JobResult
src/main/resources/
├── templates/          Thymeleaf index and HTMX fragments
└── application.properties
agent/                  opencode.json and the hiremetech-jobs skill
deploy/                 namespace, Deployment, Services, ConfigMap, ExternalSecret,
                        VMServiceScrape, Grafana dashboard
scripts/                manifest pinning and delivery-contract validation
Dockerfile              multi-stage build for the Spring Boot app
Dockerfile.opencode     OpenCode sidecar (no browser)
```

## Run locally

```bash
cp .env .env.local   # fill in the secrets, then use that file
docker compose up --build
```

`docker-compose.yml` runs both services and publishes the app on
<http://localhost:8080>. The app talks to OpenCode at `OPENCODE_BASE_URL`
(`http://opencode:4096` under compose, loopback in Kubernetes).

## Configuration

| Variable | Used by | Meaning |
|---|---|---|
| `OPENCODE_API_KEY` | opencode | Credential for the free `opencode/*` model in `agent/opencode.json` |
| `OPENCODE_PASSWORD` | both | Shared HTTP Basic password for the OpenCode V2 server (user `opencode`) |
| `OPENCODE_BASE_URL` | app | Where the app reaches the OpenCode server |
| `OPENCODE_AUTO_START` | app | Keep `false` in containers; the runtime owns its lifecycle |
| `OPENCODE_TIMEOUT_SECONDS` | app | Request budget for a search (default 600) |
| `PORT`, `METRICS_PORT` | app | Public HTTP port (8080) and internal actuator port (9090) |

The committed `.env` is gitignored. Never commit real credentials.

## Tests

```bash
./mvnw verify
```

Unit and slice tests cover the OpenCode client, the search service, both
controllers, and the Spring context. `scripts/validate-deploy.mjs` additionally
checks the delivery contract (image pins, probes, scrape target, dashboard).

## Deploy

`deploy/` is the whole deployment. Argo CD watches it directly
(`kubernetes/applications/kerenjob.yaml` in `oci-k3s-starter`), so pushing to
`master` is the deploy action:

```mermaid
flowchart LR
    push["git push to master"] --> ci["GitHub Actions<br/>build-and-push"]
    ci --> verify["verify<br/>mvnw verify · kubeconform<br/>validate-deploy.mjs"]
    verify --> build["build (arm64)<br/>app + opencode images"]
    build --> smoke["smoke tests<br/>health, UI, metrics, Basic auth"]
    smoke --> pin["update-manifest<br/>commit sha + digest pins"]
    pin --> argo["Argo CD<br/>reconciles deploy/"]
    argo --> cluster["k3s<br/>Recreate rollout"]
```

1. `build-and-push` verifies the build and manifests, publishes both arm64
   images to ghcr.io, and commits the pinned `sha-<commit>@sha256:<digest>` refs
   into `deploy/deployment.yaml`.
2. Argo reconciles the cluster to that commit.

The opencode image is rebuilt only when its inputs change
(`Dockerfile.opencode`, `opencode-entrypoint.sh`, `agent/**`, or the workflow
that builds and smoke-tests it), so app-only commits do not republish it.

Secrets never live in Git. `deploy/externalsecret.yaml` names two OCI Vault
entries and the `oci-vault` ClusterSecretStore resolves them at runtime:

```
k3s-01-kerenjob-opencode-api-key
k3s-01-kerenjob-opencode-password
```

The OpenCode V2 server always requires HTTP Basic auth (user `opencode`). If
`OPENCODE_PASSWORD` is empty it generates a random password that nothing can
read, and every client and probe gets 401 — so the password is a required
secret, shared by the server and the app, and the sidecar probes authenticate
from inside the container.

Public traffic arrives through the Cloudflare tunnel at
`kerenjob.danieliyahu.com` → `kerenjob.kerenjob.svc.cluster.local:8080`. The
service is intentionally public (no Access policy), which means anyone with the
URL can spend your model quota.

## Monitoring

- `/actuator/health`, `/actuator/prometheus` on the internal port 9090.
- `VMServiceScrape kerenjob` scrapes it every 20 s as `job="kerenjob"`.
- Grafana dashboard `kerenjob / Overview` (ConfigMap in `observability`).

## Known limits

- **Searches longer than 100 s fail in the browser.** Cloudflare closes proxied
  requests at 100 s; the POST completes server-side and shows up in the logs and
  the `kerenjob / Overview` dashboard, but the client gets a 524. This is a
  property of the current synchronous endpoint, not the deployment.
- HireMe is the only source: a rate limit (HTTP 429) or an API error fails the
  whole search. There is no fallback board.
- `Dockerfile.opencode` installs the OpenCode CLI without a version pin, so a
  rebuild follows upstream releases. Pin it if you need reproducible rebuilds.
