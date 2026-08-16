# Evidence and claim boundaries

| Claim | Evidence status | Safe wording |
|---|---|---|
| Sole human developer | Owner attestation; Git identities confirmed by owner | “Sole-human project; independently covered product, development, testing, coordination, and final decisions.” |
| Four-role workflow | Verified from source | “Implements doctor, customer-service, admin, and production portal workflows.” |
| End-to-end business implementation | Verified from source and tests | “Covers ordering through review, design, process execution, rework, billing/logistics records, and receipt confirmation.” |
| AI runtime exists | Verified from source | “Implements a configurable, governed AI assistant gateway with deterministic fallback.” |
| Autonomous Agent or RAG | Unsupported and not claimed | Do not use this description. |
| Real model enabled in production | Not established by this snapshot | Say “real-model adapters exist; production enablement and output quality require separate evidence.” |
| Automated release | Verified for the pinned Actions run | “The pinned release passed 348 backend tests and the recorded build/deploy probes.” |
| Complete manual acceptance | Not established | Keep separate from CI and deployment evidence. |
| Real payment/logistics integration | Unsupported | Describe these as human-entered business ledgers and workflow gates. |
| Physical deletion of ordinary attachments | Unsupported | “Deleted records cannot receive new authorized URLs; storage uses soft deletion.” |
| Every tracked upstream file is original work | False | Exclude `vendor/` from personal code-volume and originality claims. |
| AI-assisted development | Supported by original repository history/materials and owner statement | “Used AI tools for implementation and testing support; human owner retained product and release decisions.” |

Historical defect reports must always retain their original version and
environment. Their safe current-snapshot status is:

- BUG-012/013: the active case-group ordering path contains mandatory completed-
  STL checks, idempotency keys, optimistic versioning, and client-side mutual
  exclusion. These are code-level fixes, not a blanket concurrency proof.
- BUG-019: the pinned release verified its recorded public login/CORS probes;
  this archive does not re-verify the historical deployment.
- BUG-020: deleted records cannot receive new authorized URLs, but ordinary
  attachments remain soft-deleted and an already signed URL may work until its
  expiry.
- BUG-021: internal object-storage access and public signing endpoints are
  separated in code; a real-browser upload/preview/download run in the target
  environment remains a separate acceptance requirement.
