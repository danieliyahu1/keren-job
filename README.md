# keren-job

Describe a job in plain language; an agent logs into LinkedIn, searches, and
returns matches.

Two processes, one pod:

| Container | Image | Role |
|---|---|---|
| `app` | `ghcr.io/danieliyahu1/keren-job` | Spring Boot 4 UI/API on `:8080`; operational endpoints on `:9090` |
| `opencode` | `ghcr.io/danieliyahu1/keren-job-opencode` | `opencode serve` on `:4096`: the agent runtime, Playwright MCP and Chromium |

## Run locally

```bash
cp .env .env.local   # the committed .env is gitignored; see the variables below
docker compose up --build
```

`docker-compose.yml` runs both services and publishes the app on
<http://localhost:8080>. The app talks to OpenCode at `OPENCODE_BASE_URL`
(`http://opencode:4096` under compose, loopback in Kubernetes).

Environment:

| Variable | Used by | Meaning |
|---|---|---|
| `LINKEDIN_EMAIL`, `LINKEDIN_PASSWORD` | opencode | Substituted into the `linkedin-jobs` skill at container start |
| `OPENCODE_API_KEY` | opencode | Credential for the free `opencode/*` model in `agent/opencode.json` |
| `OPENCODE_BASE_URL` | app | Where the app reaches the OpenCode server |
| `OPENCODE_AUTO_START` | app | Keep `false` in containers; the runtime owns its lifecycle |
| `OPENCODE_TIMEOUT_SECONDS` | app | Request budget for a search (default 600) |
| `PORT`, `METRICS_PORT` | app | Public HTTP port (8080) and internal actuator port (9090) |

## Deploy

`deploy/` is the whole deployment. Argo CD watches it directly
(`kubernetes/applications/kerenjob.yaml` in `oci-k3s-starter`), so pushing to
`master` is the deploy action:

1. `build-and-push` verifies the build and manifests, publishes both arm64
   images to ghcr.io, and commits the pinned `sha-<commit>@sha256:<digest>` refs
   into `deploy/deployment.yaml`.
2. Argo reconciles the cluster to that commit.

The opencode image is rebuilt only when `Dockerfile.opencode`,
`opencode-entrypoint.sh`, or `agent/**` change, because every rebuild is a fresh
~1 GB pull for a VM with no image garbage collection.

Secrets never live in Git. `deploy/externalsecret.yaml` names four OCI Vault
entries and the `oci-vault` ClusterSecretStore resolves them at runtime:

```
k3s-01-kerenjob-linkedin-email
k3s-01-kerenjob-linkedin-password
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
URL can spend your LinkedIn session and model quota.

### Known limits

- **Searches longer than 100 s fail in the browser.** Cloudflare closes proxied
  requests at 100 s; the POST completes server-side and shows up in the logs and
  the `kerenjob / Overview` dashboard, but the client gets a 524. This is a
  property of the current synchronous endpoint, not the deployment.
- Chromium runs in a 1200 Mi container on a shared 12 GB node. Two searches at
  once will be slow; a very large LinkedIn page can push the container to its
  limit and be OOM-killed (visible as restarts in the dashboard).
- The `opencode` container keeps no persistent browser profile: every new pod
  logs in to LinkedIn again, which is the most likely place to hit a challenge.
- `Dockerfile.opencode` installs the OpenCode CLI without a version pin, so a
  rebuild follows upstream releases. Pin it if you need reproducible rebuilds.

## Monitoring

- `/actuator/health`, `/actuator/prometheus` on the internal port 9090.
- `VMServiceScrape kerenjob` scrapes it every 20 s as `job="kerenjob"`.
- Grafana dashboard `kerenjob / Overview` (ConfigMap in `observability`).
