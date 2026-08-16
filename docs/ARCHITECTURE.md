# Architecture

```mermaid
flowchart LR
    Doctor["Doctor portal"] --> API["Spring Boot API"]
    CS["Customer-service portal"] --> API
    Admin["Admin portal"] --> API
    Worker["Production portal"] --> API
    API --> MySQL[(MySQL)]
    API --> Redis[(Redis)]
    API --> MinIO[(Private MinIO bucket)]
    API --> AI["Governed AI gateway"]
    AI --> Fallback["Deterministic fallback"]
    AI -. "when explicitly configured" .-> Model["DeepSeek / compatible model"]
```

The frontend is one Vue SPA with four portal entry modes. The backend is best
described as a modular monolith: Maven declares domain modules, while most
runtime business implementation currently lives in `platform-server` and uses
a limited incremental bridge to a pinned RuoYi-Vue-Pro subset. This archive
does not contain or claim a full RuoYi runtime integration.

## Main business sequence

```mermaid
flowchart LR
    A["Create patient and case group"] --> B["Upload and submit materials"]
    B --> C["Customer-service review"]
    C --> D["Production review"]
    D --> E["Design task and doctor confirmation"]
    E --> F["Execute process DAG"]
    F --> G{"Inspection result"}
    G -- Pass --> H["Billing and logistics"]
    G -- Fail --> I["Targeted rework"]
    I --> F
    H --> J["Doctor confirms receipt"]
```

## Key code areas

- Portal assembly: `frontend/src/App.vue`
- Doctor case-group flow: `frontend/src/doctor/DoctorCaseGroupWizard.vue`
- Authentication and portal mapping: `backend/platform-server/.../bootstrap/`
- Case-group and order creation: `backend/platform-server/.../order/`
- Workflow runtime and execution: `backend/platform-server/.../workflow/`
- Design lifecycle: `backend/platform-server/.../design/`
- File governance: `backend/platform-server/.../file/`
- Billing/logistics and messages: `backend/platform-server/.../collaboration/`
- AI gateway: `backend/platform-server/.../ai/`
