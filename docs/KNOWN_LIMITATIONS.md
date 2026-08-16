# Known limitations and technical debt

- Several files carry too much responsibility, notably the root Vue assembly,
  workflow execution service, and AI gateway service.
- The declared Maven domain modules are mostly boundary scaffolding; most
  runtime implementation remains concentrated in `platform-server`.
- Some enumerated order states are not driven by a central transition table;
  legal transitions are distributed across callers and the projected status
  may skip intermediate quality stages.
- The bearer access token is a custom signed two-part token, while the OpenAPI
  description historically labelled the scheme as JWT.
- Ordinary attachment deletion is logical/soft deletion. New signed access is
  denied, but objects are not physically removed and previously signed URLs may
  remain valid until expiry.
- AI defaults to deterministic fallback. The snapshot does not prove a real
  model is enabled or evaluated in production.
- Billing and logistics are internal records, not payment-gateway, carrier API,
  label, tracking-webhook, or invoicing integrations.
- Some account, invitation, online-payment, and invoice-request UI paths remain
  explicitly unavailable.
- CI/deployment evidence is version-specific; it does not replace complete
  four-role manual acceptance using synthetic files and data.
