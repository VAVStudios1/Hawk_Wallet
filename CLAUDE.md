# Hawk Wallet — Development Guide

Hawk Wallet is a financial application. Correctness, auditability, and security
take priority over speed of delivery. When in doubt, ask rather than assume.

## Project Structure

```
Hawk_Wallet/
├── Frontend/   Android app — Kotlin + Jetpack Compose
└── Backend/    Django + Django REST Framework API
```

Each side is independently buildable. The backend is the single source of
truth; the Android app never performs financial calculations locally.

## Android Stack & Architecture

- Kotlin + Jetpack Compose + Material 3. Package root: `com.example.hawk_wallet`.
- MVVM: Composable screens → ViewModel (StateFlow/UiState) → Repository → API
  client. No business logic or financial math in Composables.
- Navigation via Navigation-Compose; no manual FragmentManager/Activity chains.
- Networking via Retrofit/OkHttp (or Ktor) with a single authenticated client;
  attach JWT via an interceptor, not per-call.
- All monetary amounts arrive from the backend as strings/decimals and are
  displayed as-is or formatted for display only — never recomputed, rounded,
  or summed on-device for anything that affects a balance shown as truth.
- Auth tokens stored in EncryptedSharedPreferences or DataStore backed by
  Android Keystore. Never store tokens, PANs, or secrets in plain
  SharedPreferences, logs, or crash reports.
- Follow the existing version catalog (`libs.versions.toml`) for all
  dependencies; don't hardcode versions in `build.gradle.kts`.

## Backend Stack & Architecture

- Python + Django + Django REST Framework. PostgreSQL is the intended
  production database (SQLite is dev-only, never assume SQLite semantics).
- Apps organized by domain (e.g. `accounts`, `wallets`, `transactions`,
  `payouts`), not one monolithic app.
- Backend is the sole source of truth for balances, ledger state, and every
  financial calculation. The client never dictates a balance or fee — it only
  requests an action; the server computes and validates the result.
- Business logic lives in service/model layers, not in views/serializers.
  Views validate input and call services; services enforce invariants.
- Settings are environment-driven (`.env` via python-dotenv). Never hardcode
  `SECRET_KEY`, DB credentials, or API keys in `settings.py`.

## API & Authentication

- DRF + `djangorestframework_simplejwt` for token auth. Access/refresh token
  flow only — no long-lived static tokens for user-facing auth.
- Every endpoint declares explicit permission classes. No endpoint is
  implicitly public; anonymous access must be a deliberate, reviewed choice.
- Validate and serialize all input/output explicitly via DRF serializers —
  never trust client-supplied IDs, amounts, or ownership without a server-side
  check against the authenticated user.
- Document endpoints via drf-spectacular as they are built.

## Financial Data Rules

- **Never use `float`/`double` for money.** Use Python `Decimal` end-to-end on
  the backend, and DRF `DecimalField` (fixed `max_digits`/`decimal_places`) in
  serializers and models. On Android, treat amounts as `String`/`BigDecimal`
  for any local math — never `Float`/`Double`.
- All monetary DB columns are fixed-precision decimal, never floating point.
- Every balance-changing operation must be atomic (DB transaction) and
  idempotent where the client may retry (e.g. idempotency keys on
  payment/payout creation).

## Wallet, Payment, Payout & Commission Safety

- Never allow duplicate payments or payouts — enforce idempotency keys and/or
  unique constraints at the database level, not just application checks.
- Never allow over-payment or a balance to go negative unless a specific rule
  explicitly permits it (e.g. an approved credit line) — enforce with DB
  constraints, not just serializer validation.
- Every payout must be independently tracked as its own record (not inferred
  from a transaction log) and must support a UTR/bank reference number field.
- Every financial state change (payment, payout, refund, commission,
  adjustment) must write an immutable audit/ledger entry: who, what, amount,
  before/after balance, timestamp, and reference ID. Never hard-delete or
  mutate historical financial records — corrections are new offsetting
  entries, not edits.
- Commission calculations happen once, on the backend, at the time of the
  triggering transaction, and are stored — never recomputed on read.

## Role-Based Access Control

- Define roles explicitly (e.g. user, merchant, admin, support) and enforce
  them via DRF permission classes on every view — never via frontend UI hiding
  alone.
- A user may only read/modify their own financial data unless their role
  explicitly grants broader access; check ownership server-side on every
  request, including nested/related objects.
- Privileged actions (manual balance adjustment, payout approval, refunds)
  require an elevated role and must produce an audit entry identifying the
  actor.

## Security Rules

- Never weaken authentication or authorization for convenience (no debug
  bypass flags, no "temporary" open endpoints, no shared service accounts for
  user actions).
- Secrets (`SECRET_KEY`, DB credentials, JWT signing keys, third-party API
  keys) live only in environment variables / `.env` (gitignored), never in
  source or commit history.
- `DEBUG = False` and a real `ALLOWED_HOSTS` in any non-local environment.
- CORS is explicit and scoped to known origins — never `CORS_ALLOW_ALL_ORIGINS`
  in production.
- Validate and sanitize all input server-side regardless of client-side
  validation. Never trust client-computed amounts, statuses, or IDs.
- Log security-relevant and financial events without logging secrets, full
  card/account numbers, or tokens.

## Testing Expectations

- Backend: pytest-django for model, service, and API-level tests. Any code
  touching balances, payments, payouts, or commissions requires tests
  covering the happy path, duplicate/retry attempts, and boundary/over-payment
  cases before it's considered done.
- Frontend: unit tests for ViewModels/state logic; instrumented tests for
  critical flows (login, balance display, payment/payout initiation).
- Do not mark financial logic complete without a test proving atomicity/idempotency
  (e.g. simulated concurrent or duplicate requests).

## Git & Commit Expectations

- Small, focused commits with clear messages describing the "why."
- Never commit `.env`, `venv/`, `db.sqlite3`, build artifacts, or secrets —
  respect the existing `.gitignore`.
- No direct commits of generated migrations without reviewing the resulting
  schema change, especially for financial models.
- Do not push or force-push without explicit instruction.

## Ambiguity Rule

If a requirement, business rule, or financial behavior is not clearly
specified (e.g. rounding rules, fee timing, refund policy, role permissions),
**do not invent it** — ask before implementing.
