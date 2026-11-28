# What's new, and how to try it

For the owner's own testing. Commit `466906b1`, 2026-11-25. Each line takes a minute or two. Use made-up documents only.

**Before you start**
- Top bar with Cases, New case, Review? Good. A menu at the left with "Dashboard" means the frontend was built without `VITE_CASE_FILE_UI_ENABLED=true`.
- In a local start the account menu has **Test role · local only**. With real sign-in use one user per role. Two people are needed for four eyes and for approval links.
- Marked **(steps)**: documents are processed in steps (the staged pipeline). Since task 0121 that is the only way, so these always show.
- More detail: the guides in the [README](README.md).

## Document Processor

| New | Where | Try it |
|---|---|---|
| Entity always shown, and a switcher | Top, left of your name | With two entities: type something in New case, switch, see the question, choose **Stay** |
| Case fields of every kind, sensitive and with several values | New case | Fill a sensitive field with **Add another**; open the case: the value is there, on Cases it is not |
| Priority | New case; case panel | Make a case **Urgent**; it is first under **Needs you** and says Urgent |
| Page to check | Review | A poor scan or handwriting (with the vision model off every hard page comes): draw two boxes, **Remove mark**, try to finish without the tick |
| Second check | Review | Business Admin switches it on; a page may come back with "Second check found something on page …" |
| Reject case | Case panel, Review | Reject a test case as **Duplicate**; it is Stopped, "Rejected: duplicate", and can't be undone |
| Error-code guide | Account menu › **Help: error codes** | Search "password"; choose an LV code on any screen |
| Waiting for you **(steps)** | Top bar, only while a destination refused something | Make a test destination refuse; **Resend this case** |

## Document Supervisor

Everything above, and:

| New | Where | Try it |
|---|---|---|
| Bulk approve | Cases › **Approval** tile | Approval before delivery on; tick two cases, **Approve selected (2)** |
| Four eyes | The same list | Review a case yourself: it can't be ticked. Note: its own **Approve** button still works |
| Reject at approval | Case panel | **Reject case** on a case in Approval |
| Approval link | The e-mail | Needs notifications and the link key (`../install/NOTIFICATIONS.md`) and a second person as submitter. Open it: nothing happens until you choose **Approve delivery** |

## Business Admin

| New | Where | Try it |
|---|---|---|
| Entities and time zones | Admin › **Entities** | **Rename** one; set a **Time zone**; try to suspend the entity you are in (not offered) |
| Redaction catalog and packs | Admin › **Redaction & retention** | Turn on **FRCP 5.2**: types and the second check come on. Card number, CVV and PIN can't be turned off |
| Keep the last 4 | The same card, **What stays visible** | Set it for SSN, save, send a new typed document |
| Person name, signature, face photo | The same card, **Sensitive types** | Turn on **Person name**: every name comes to Review |
| Second check | The same card | **Check the finished file a second time** |
| Case fields | Admin › **Case fields** | Add a field "Borrower name": Sensitive is suggested. Edit a field that has values: its type is locked |
| Business messages | Admin › **Notifications** | Change how often "Cases waiting for approval" is sent |
| Reports tabs | **Reports** | Open **Daily intake and delivery** and **Operations**; **Export CSV** on each |
| Licensing | Admin › **Licensing** | Read-only; under the demo licence it says Test environment |

Can't: open cases. A count on Reports is not a link for this role.

## IT Admin

| New | Where | Try it |
|---|---|---|
| Priority of a source | Admin › **Integrations** › Receiving | Set **Priority of cases from this source** |
| Migration (Bulk) | The same place | Switch it on (the entity needs a time zone); change the times; switch off and read the question **(steps for the times)** |
| Notifications | Admin › **Notifications** | Set up a mail account; **Send me a test message** |
| Change the sign-in | Admin › **Sign-in** | **Prepare a change**, **Test the draft**, **Discard draft**. Don't switch on a system in use |
| Queue and time left | Admin › **Platform operations** | Send 20 documents and watch; **How is this worked out?** **(steps)** |
| Status and bundle | **Support** › Status | **Download diagnostic bundle** and look inside |
| First entity of a new install | Command line | `../install/SETUP.md`, "After setup" |
| Back-up and restore | Command line | `../install/backup-and-restore.md` |

Can't: open cases or reports.

## Report Viewer

| New | Where | Try it |
|---|---|---|
| Reports tabs | **Reports** | Overview and Daily are full; **Operations** says more detail is for Business Administrators. No **Export CSV** |
| Error-code guide | Account menu | As above |

## Platform Operations

| New | Where | Try it |
|---|---|---|
| Queue of every entity | Admin › **Platform operations** › Overview | Title "Queue, every entity together" **(steps)** |
| Workers | The same, **Workers** | **Change maximums**, then empty it again |
| Status | **Support** › Status | Read only; the bundle is the IT Admin's |
