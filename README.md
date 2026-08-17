# AI-assisted dental ordering and production collaboration platform

> Public, sanitized portfolio source snapshot for academic and professional review.

This project models the end-to-end collaboration between dental clinics and a
dental laboratory. A single Vue application exposes four permission-separated
portals—doctor, customer service, administrator, and production worker—backed
by a Java/Spring Boot modular monolith.

The system covers patient/case-group ordering, file submission, customer-
service review, production review, design confirmation, process-DAG execution,
quality inspection and rework, billing/logistics records, and doctor receipt
confirmation. It also contains a governed AI assistant gateway for missing-
material checks, FAQ, product recommendations, customer-service queries, and
production-note assistance.

## What this archive proves

- Substantial four-role business implementation rather than a static demo.
- Vue 3/TypeScript/Vite frontend and Java 21/Spring Boot backend.
- MySQL, Redis, MinIO, Flyway, Docker Compose, and an OpenAPI contract.
- Fine-grained roles, permission codes, data scopes, file-access checks,
  idempotency, optimistic locking, workflow snapshots, and rework gates.
- Configurable DeepSeek/LangChain4j adapters with audit, rate, budget,
  retry/circuit-breaker, output-guard, and deterministic-fallback paths.
- A frozen release whose source workflow recorded 350 backend tests with zero
  failures and completed build, deployment, and public login/CORS probes.

It does **not** claim autonomous Agent/RAG behavior, production use of a real
model, external payment/logistics integration, physical deletion of ordinary
attachments, or completion of every manual acceptance scenario.

## Suggested review path

1. [`docs/TEACHER_BRIEF_ZH.md`](docs/TEACHER_BRIEF_ZH.md)（中文审阅入口）
2. [`docs/PROJECT_REVIEW_BRIEF.md`](docs/PROJECT_REVIEW_BRIEF.md)
3. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
4. [`docs/EVIDENCE_AND_CLAIMS.md`](docs/EVIDENCE_AND_CLAIMS.md)
5. [`docs/VERIFICATION.md`](docs/VERIFICATION.md)
6. `frontend/src/doctor/DoctorCaseGroupWizard.vue`
7. `backend/platform-server/src/main/java/com/yuri/aiorder/order/casegroup/`
8. `backend/platform-server/src/main/java/com/yuri/aiorder/workflow/`
9. `backend/platform-server/src/main/java/com/yuri/aiorder/ai/`
10. `backend/platform-server/src/test/java/com/yuri/aiorder/`

## Local verification

Prerequisites: Java 21, Node.js 22, pnpm 11, Docker, and a Bash shell
(Git Bash or WSL on Windows).

```bash
corepack enable
corepack prepare pnpm@11.7.0 --activate
pnpm install --frozen-lockfile
pnpm security:scan
docker compose up -d --wait mysql redis minio
bash scripts/ensure-test-database.sh
TZ=Asia/Shanghai bash scripts/with-jdk21.sh ./mvnw -Duser.timezone=Asia/Shanghai -f backend/pom.xml test
pnpm build:frontend
docker compose down -v
```

The included Maven Wrapper downloads the pinned Maven distribution on first
use, so a separate Maven installation is not required.

The committed configuration is local-only and uses deterministic AI fallback.
Do not add real credentials to this repository. See
[`docs/SECURITY_AND_PRIVACY.md`](docs/SECURITY_AND_PRIVACY.md).

## Authorship and AI-assisted development

The repository owner reports that this was a sole-human-development project:
one person held the product, development, testing, coordination, and internal
acceptance, release, and delivery-decision roles. Customer and business-side
participants supplied requirements and feedback. Separate PM/developer/test
identities were used to simulate team perspectives and four-role workflows,
rather than representing three human developers.

AI tools assisted with planning, code generation, debugging, scripts,
documentation, and automated testing. Product judgement, requirement
interpretation, manual testing, risk classification, and release decisions
remained with the human owner. Some AI-coauthor activity is recorded in the
original source history referenced by `SOURCE_PROVENANCE.md`; this sanitized,
no-history archive does not preserve that commit history.

## Archive boundaries

This repository was rebuilt without the original Git history. It excludes
customer evidence, patient/case data, raw STL/DICOM files, screenshots and
recordings, signed URLs, database dumps, deployment secrets, local virtual
environments, generated artifacts, and internal agent profiles.

See [`SOURCE_PROVENANCE.md`](SOURCE_PROVENANCE.md),
[`PUBLIC_PORTFOLIO_NOTICE.md`](PUBLIC_PORTFOLIO_NOTICE.md), and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
