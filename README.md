# hermes-backend

The triage API for **Hermes**, a self-hosted mail triage service. It reads Gmail, decides what is
worth interrupting you for, pushes the few things that are, and rolls everything else into one
daily digest.

Spring Boot 4.1 / Java 25 / PostgreSQL. Deployed to `k3s-01` via
[`hermes-deployment`](https://github.com/Janne6565/hermes-deployment).

## What it does

```
Gmail ──poll──►  rules ──miss──►  claude-sidecar ──►  Postgres
                   │                    │ (unavailable)
                   │                    └──► fallback: stored as `normal`, retried at 03:00
                   ▼
              high ──► ntfy (urgent)          everything ──► 18:00 digest ──► ntfy (default)
Grafana ─webhook─►  severity routing ──► push now | hold for digest
SigNoz  ─webhook─►
```

**The pipeline, in order.** Hard rules first — they are deterministic, editable at runtime, and
tiered so an allowlist hit always beats a `List-Unsubscribe` block. Anything unmatched goes to the
classifier sidecar over pod-local HTTP. If the sidecar is down, out of credit, or slow, the message
is stored as `normal` and flagged `fallback`: it never pushes, it shows as a warning line in the
digest, and the 03:00 job re-classifies it once the sidecar is healthy again.

**Interrupting is centralised.** `NotificationService` is the only code path that can make the
phone buzz, and both gates — shadow mode and quiet hours — live there rather than at the call
sites.

## Configuration

Everything is bound once at startup from `hermes.*` (see `HermesProperties`). The values that
matter:

| Property | Default | Notes |
|---|---|---|
| `hermes.shadow-mode` | `true` | Classify and store, push nothing. Phase 3 runs here for a few days. |
| `hermes.gmail.refresh-token` | *(unset)* | Unset disables the polling path entirely; the rest of the service still comes up. |
| `hermes.gmail.poll-interval` | `180s` | |
| `hermes.gmail.snippet-length` | `500` | Characters of plaintext sent to the classifier. Data minimisation, not a display cap. |
| `hermes.sidecar.base-url` | `http://127.0.0.1:8081` | Pod-local only — never a Service, never an Ingress. |
| `hermes.digest.send-time` | `18:00` | In `hermes.timezone`. |
| `hermes.alerts.webhook-secret` | *(unset)* | Unset means the webhook rejects everything — it fails closed. |
| `hermes.retention.message-days` | `90` | Enforced nightly at 03:30. |

## API

`GET /swagger-ui.html` for the full surface; `/v3/api-docs` is what the frontend's Orval client
generates from.

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/digest/today` | Live digest — the Janus widget's source |
| `GET /api/v1/digest/{date}` | A historical digest, read back exactly as delivered |
| `GET /api/v1/messages` | Search the local Postgres index (works while sync is down) |
| `GET /api/v1/messages/high/open` | High-priority items not yet dismissed |
| `POST /api/v1/messages/{id}/dismiss` | Clear an item; the mail in Gmail is untouched |
| `GET/POST/DELETE /api/v1/rules` | Rule management |
| `POST /api/v1/rules/feedback` | "This shouldn't have pinged me" → auto-creates the rule |
| `POST /api/v1/events/alert` | Grafana / SigNoz intake (`X-Hermes-Token`) |
| `GET /api/v1/health` | Health screen + widget traffic light |
| `GET /actuator/health` | k8s liveness / readiness |

## Running locally

Needs a Postgres. With no Gmail credentials the service starts fine, polls nothing, and the health
screen reports the mailbox as never synced — which is the intended developer experience.

```sh
docker run -d --name hermes-pg -p 5432:5432 \
  -e POSTGRES_DB=hermes -e POSTGRES_USER=hermes -e POSTGRES_PASSWORD=hermes postgres:17
./mvnw spring-boot:run
```

```sh
./mvnw verify          # tests + build
./mvnw spotless:apply  # formatting (AOSP google-java-format)
```

Tests run against H2 with Flyway off — the migrations are Postgres-specific (`jsonb`, partial
indexes) and are exercised in the cluster.

## Getting a Gmail refresh token

1. Google Cloud project → enable the Gmail API → OAuth consent screen in **testing** mode.
2. Create an OAuth **Desktop app** client.
3. Run the consent flow once for scope `https://www.googleapis.com/auth/gmail.readonly` and keep
   the refresh token.
4. Store client id, client secret and refresh token in the `gmail-oauth` sealed secret.

The scope stays `gmail.readonly` until phase 6. This service reads mail; it never writes it.

## Security notes

- The classifier only ever receives sender, subject and a 500-character plaintext snippet. Full
  bodies and attachments are never read out of the API response.
- HTML is never parsed or rendered server-side.
- `POST /api/v1/events/alert` is the only externally reachable endpoint. It authenticates with a
  shared secret compared in constant time, and fails closed when the secret is unset.
- Gmail refresh token and ntfy token are account-level credentials: sealed secrets only, never
  logged.
