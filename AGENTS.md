# Repository guidance for coding agents

This is a private, sanitized portfolio snapshot, not the operational project.

## Hard boundaries

- Never add production credentials, customer records, patient information,
  signed URLs, database dumps, raw STL/DICOM files, screenshots, recordings, or
  original acceptance archives.
- Never connect this repository to a production database, object store, server,
  or AI key as part of routine review.
- Keep `.env` local. Only `.env.example` may be committed.
- Run `pnpm security:scan` before every commit.
- Preserve the source coordinate and claim boundaries in
  `SOURCE_PROVENANCE.md` and `docs/EVIDENCE_AND_CLAIMS.md`.
- Do not present deterministic AI fallback as proof of a live model, or the
  assistant gateway as an autonomous Agent/RAG system.
- Keep the RuoYi upstream license and attribution intact.

## Working conventions

- Use `codex/` branches for repository changes.
- Keep `main` reviewable and free of generated files.
- Prefer small, source-focused changes and document any claim-affecting change.
- Local tests may use only synthetic data and isolated MySQL/Redis/MinIO
  instances.
