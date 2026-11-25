# LexaVault guides

The repository copy is the source of truth. Copies elsewhere are made from it and name their date.

| Guide | For |
|---|---|
| [`WHATS_NEW.md`](WHATS_NEW.md) | The owner's own testing: what is new, by role, and how to try it |
| [`business/BUSINESS_USER_TRAINING.md`](business/BUSINESS_USER_TRAINING.md) | Document Processor, Document Supervisor, Business Admin, Report Viewer |
| [`tenant-it/TENANT_IT_SUPPORT_GUIDE.md`](tenant-it/TENANT_IT_SUPPORT_GUIDE.md) | IT Admin |
| [`platform-operations/PLATFORM_SUPPORT_OPERATIONS_GUIDE.md`](platform-operations/PLATFORM_SUPPORT_OPERATIONS_GUIDE.md) | Platform Operations, support |
| [`../install/`](../install/) | IT, for installing and settings |
| [`TRAINING_CHANGELOG.md`](TRAINING_CHANGELOG.md) | What changed in the guides, and when |
| [`TRAINING_GOVERNANCE.md`](TRAINING_GOVERNANCE.md) | Rules for keeping them right |

In the app: account menu › **Help: error codes**, and the "?" beside each setting in Admin.

**Checked 2026-11-25 (task 0095)** against commit `466906b1`: the code, and the screens with made-up answers. Not against the real app with a real sign-in.

**Which screens.** The guides describe the Case File screens (a bar at the top: Cases, New case, Review, Reports, Admin, Support). They show only in a frontend built with `VITE_CASE_FILE_UI_ENABLED=true`; without it the older screens appear (a menu at the left with "Dashboard"), and these guides do not fit.

**Pictures** in `assets/` come from the mocked browser harness: from `frontend/securedoc-ui`, `GUIDES0095=1 npx playwright test --config playwright.a11y.config.ts guides0095 --project light`. Look at each one before keeping it.

A change that people see is not done until its guide is updated (`TRAINING_GOVERNANCE.md`).
