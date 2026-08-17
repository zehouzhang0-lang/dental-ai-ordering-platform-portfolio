# Archive verification

This page records checks performed against the sanitized portfolio archive,
not against the historical production environment.

## Local verification on 2026-08-17

| Check | Result |
| --- | --- |
| Frozen dependency install (`pnpm install --frozen-lockfile`) | PASS |
| Frontend production build (`pnpm build:frontend`) | PASS |
| Backend reactor package on Java 21 | PASS |
| Backend test suite with isolated MySQL, Redis, and MinIO | **350 passed, 0 failed, 0 errors, 0 skipped** |
| Docker Compose configuration and service health | PASS |
| Backend container image build | PASS |
| Frontend multi-stage container image build | PASS |
| Repository path, secret-pattern, PII-pattern, binary, and size gate | PASS |

The integration run used a dedicated Docker Compose project, high local host
ports, a test-only database, synthetic accounts, and deterministic AI fallback.
It did not contact the historical production database, object store, or model
provider.

The business day is defined in `Asia/Shanghai`. Backend verification therefore
sets `TZ=Asia/Shanghai` and passes `-Duser.timezone=Asia/Shanghai` to Maven,
matching the database and container configuration. This prevents UTC runner
midnight from shifting date-range assertions by one business day.

The frontend build emitted Vite's advisory for large output chunks. That does
not fail the build, but bundle splitting and performance measurement remain
future work.

## What this result does not prove

- It is not a substitute for complete browser-based four-role manual acceptance.
- It does not verify the current state of any historical public deployment.
- It does not prove real-model production usage or external integrations.
- It does not restore evidence deliberately excluded for privacy and licensing.

See [`EVIDENCE_AND_CLAIMS.md`](EVIDENCE_AND_CLAIMS.md) for the distinction
between code-verified, workflow-verified, owner-stated, and unverified claims.
