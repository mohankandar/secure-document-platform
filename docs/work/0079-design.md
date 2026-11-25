# Task 0079: design (Staged-Pipeline-Design.md, section 8, copied)

## 8. Migration: Bulk
- IT marks a source system as **Bulk**. Its cases are a class below Low.
- **Time window per Bulk source. Default: weeknights 7 PM to 6 AM, plus weekends.** IT can change it per source.
- **Outside the window:** no new Bulk jobs start. A running job finishes. The source can keep dropping files; they wait.
- **Live work always first:** Bulk jobs run only when no live jobs wait in that stage. Even then they use **at most half** of each stage's workers, and **never the last AI worker**.
- A reviewer can still make one migration case Urgent.

