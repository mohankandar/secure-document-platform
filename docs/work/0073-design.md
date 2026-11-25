# Task 0073: design (staged-pipeline-design.md, section 7, copied)

## 7. Priority (agreed 2026-11-20)

- **Urgent / Normal / Low**, Normal by default.
- **Where it's set:**
  - per case at intake (API field, trigger file, New case choice);
  - each source system can have a default;
  - **any reviewer can change it**, and the change is audited.
- **Order** in every stage queue and in the Review list: Urgent → Normal → Low → Bulk, oldest first within each.
- **No starvation:** while Urgent and Normal work are both waiting, **one job in every five** taken from a stage goes to the oldest Normal job. Low gets the same guarantee against Normal.
- Changing a case's priority moves its waiting jobs at once. A job already running finishes.
- **Fairness between entities** (owner, 2026-11-21): within the same priority, workers take turns between entities (and between customers on hosted), so one customer's flood never pushes another customer's daily work back.
