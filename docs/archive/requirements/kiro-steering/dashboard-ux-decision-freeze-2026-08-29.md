Dashboard UX Decision Freeze - 2026-08-29
Approved V1 dashboard and business-field behavior
Project Codename: LexaVault

# Locked Decisions

- One shared operational Dashboard for all business roles; roles control permissions/actions, not separate page designs.
- Top Overview/KPI section is collapsible and its cards act as one-click stage filters.
- Stage quick filters include All, Processing, Needs Review, Needs Approval, Exported, Technical Attention, and Rejected. Needs Approval reflects the Unit 22 final-delivery approval hold.
- Search spans Business Admin-configured searchable fields.
- A Business Record supports multiple business identifiers. Exactly one is Primary, while additional identifiers can also be required, searchable, extractable, visible, and ordered.
- Dashboard never uses a generic `Reference` label when a configured label such as `Contract Number` or `Member Number` is available.
- Default operational columns are Primary Business Identifier, Received, Stage, Documents, and Attention. Additional approved identifiers/attributes can be shown as extra columns.
- Business Admin controls available/default fields and default order; individual users can show/hide/reorder approved columns.
- Internal UUIDs and workflow IDs remain hidden from normal business views.
- Manual Review is a focused exception workbench, not a developer task console.
- Filtering/sorting/pagination should be performed server-side as scale requires.

# Visual Direction

See `03_Dashboard_Target_Mockup_2026-08-29.png`. The mockup demonstrates the desired clean/light SaaS direction, collapsible overview, stage chips, search, configurable columns, and single-dashboard approach. Functional requirements in the Markdown documents remain authoritative over pixels/sample data.
