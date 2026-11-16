# Hermes agent access (Experimental, fork-only)

Status: experimental, default OFF, lives only in the `kvnloo/noop` fork. It is **not** an upstream
candidate: [SCOPE.md](SCOPE.md) allows only a one-way self-hosted export, and NOOP "does not fetch rows,
commands, settings…". This feature answers requests and runs two in-app actions, so it is two-way by design.

## What it does

The owner's own Hermes, on the owner's own Tailscale network, can:

- read bounded summaries of NOOP data through the same tool names as the desktop `NoopLocalAccess`
  CLI/MCP (`health_snapshot`, `metric_series`, `data_freshness`, `sleep_summary`, `workout_summary`);
- if the owner allows it for that session, run two safe, reversible actions: `open_screen`
  (navigation only) and `show_coach_note` (a local notification).

No adb, no NOOP server, no account, no third-party relay.

## Transport and identity

```
Hermes (groot) ──stdio MCP──▶ Tools/hermes-agent/noop-agent-mcp ──HTTP JSON-RPC over WireGuard──▶ NOOP on the phone
                                  │  Presidio PII scan + receipts                               (100.x:8765/mcp, grant only)
                                  └─ token from z0live pairing (0600)       NOOP ──HTTPS──▶ z0live broker /v1/devices/self
```

- **MCP** is the tool protocol (Hermes is already an MCP client). ACP was evaluated and rejected for this
  direction: ACP is how a client drives an agent session (editor → Hermes); here Hermes is the caller and
  the phone exposes tools. A later "ask my Hermes from NOOP Coach" button would be the ACP direction.
- **Identity reuses z0live pairing.** `noop_agent_pair.py pair <phone>` mints an ordinary z0live device
  credential on the owner's broker, keeps it on the Hermes host (0600), and delivers it once to the phone
  through a single-use tailnet page (`noop-agent://pair?…`). NOOP stores only the token's SHA-256 and the
  broker's device id, and re-checks the presented token against `GET /v1/devices/self` every 5 minutes.
  `noop_agent_pair.py revoke` (or `z0live` device revoke) therefore cuts access within 5 minutes.
- **Tailscale is the network boundary.** NOOP binds only its Tailscale IPv4 address, drops peers outside
  100.64.0.0/10 and fd7a:115c:a1e0::/48 before reading, and refuses any request with an `Origin` header
  (blocks DNS-rebinding pages). Traffic is WireGuard-encrypted end to end.

## Consent, bounds, audit

- Nothing listens until the owner taps **Allow Hermes access** (15 min, 1 h or 4 h). The grant is
  memory-only, so a process restart is OFF. A foreground notification shows the expiry and a Stop button.
- Scopes per grant: summaries always; **daily values** and **safe actions** are separate opt-in ticks.
- Days are clamped to 31; responses capped at 256 KiB; request body 64 KiB; 5 s socket timeouts.
- Never returned: device ids/serials/MACs, journal or workout notes, coach messages, GPS routes, raw
  sample streams, sleep-stage JSON. Sources are labelled by kind (`oura`, `whoop (NOOP-computed)` …).
- Every request, refusal, grant, stop, expiry and pairing change is written to an on-phone audit log
  (last 300 entries, shown in the screen). The log records tool, outcome, peer, scope and size — never
  values or note text. **Forget pairing** and **Clear audit log** are one tap.
- No BLE command path is reachable from this API.

## Hermes side

`Tools/hermes-agent/` (runs on the Hermes host, not in the app):

| File | Role |
| --- | --- |
| `noop-agent-mcp` | stdio MCP server for Hermes: the seven phone tools plus `coach_dashboard` |
| `noop_agent_mcp.py` | transport, Presidio redaction, receipts, ephemeral dashboard renderer |
| `noop_agent_pair.py` | pair / revoke / status using the z0live broker store |
| `e2e_phone.py` | no-adb end-to-end check; writes structure/latency only |
| `tests/` | `python -m unittest discover -s tests` |

Register in the Hermes profile `config.yaml`:

```yaml
mcp_servers:
  noop:
    command: /path/to/noop/Tools/hermes-agent/noop-agent-mcp
    timeout: 60
    connect_timeout: 30
```

Every phone response passes through Microsoft Presidio (`presidio-analyzer` + spaCy `en_core_web_sm`)
before Hermes sees it; a regex fallback runs if Presidio is missing and each result reports which
engine ran. Receipts (`~/.local/state/noop-agent/receipts.jsonl`) hold tool, status, size and redaction
count only.

`coach_dashboard` renders a static, script-free HTML page from live summaries plus the model's advice,
with facts and inference in separate sections, freshness/staleness, sources and a not-medical-advice
footer. It is written 0600 under `$TMPDIR/noop-coach/` and deleted after 24 h.

## Owner steps

1. NOOP → Settings → Advanced → **Hermes agent access**.
2. On the Hermes host: `Tools/hermes-agent/noop_agent_pair.py pair <phone-tailscale-hostname>`; open the
   printed link on the phone, tap **Open NOOP & pair**, then **Confirm pairing from link**.
3. Pick a duration, tick what you allow, tap **Allow Hermes access**.
4. Ask Hermes, e.g. "How did I sleep this week and what should I do today? Make me a dashboard."
5. Stop any time from the notification; **Forget pairing** or `noop_agent_pair.py revoke` to end it for good.
