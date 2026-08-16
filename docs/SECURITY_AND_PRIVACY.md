# Security and privacy boundary

This archive follows a data-minimization rule: source needed for technical
review is retained; operational evidence and identity-bearing material are not.

## Excluded by design

- Original Git history and historical secret literals
- `.venv`, generated builds, IDE state, agent profiles, and office profiles
- Customer PRD/feedback, production checklists, addresses, and live endpoints
- Patient, clinic, order, account, message, and attachment evidence
- Raw STL/DICOM and other medical geometry
- Screenshots, recordings, narration, archives, database exports, and logs
- Real `.env` files, API keys, passwords, SSH material, and signed object URLs

The main Flyway migrations contain roles, permissions, and schema only. The
four known local login identities required by the automated test suite were
moved to `src/test/resources/db/testdata/`, which is loaded only by the test
profile.

## Safe review behavior

- Use only synthetic data in isolated local services.
- Leave AI in deterministic mode unless the repository owner explicitly
  authorizes a local-only model evaluation.
- Never paste credentials into issues, commits, screenshots, or reports.
- Run `pnpm security:scan` before committing.
- Treat any newly added binary or evidence file as blocked until individually
  reviewed for privacy and redistribution rights.
