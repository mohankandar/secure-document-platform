# Task 0089: keyboard-only pass, per screen

Date: 2026-11-25. Real key presses in Microsoft Edge (Playwright), light and dark: `frontend/securedoc-ui/e2e-a11y/keyboard0089.spec.ts` (31 tests). No mouse, except to draw a mark on a page (needs a pointer, known exception). No screen reader was used.

"Walked" means: Tab from the top to the end of the screen or dialog. Every control that can be used was reached, each stop came after the one before it in reading order and inside the window, each had a ring of at least 2 px, nothing is placed in another order than it is read, and Tab left at the end. In a modal dialog, Tab goes to the dialog's controls and the browser's own, never to the page behind.

| Screen | What was pressed | What happened | Problems |
|---|---|---|---|
| 0058 Entities | Walked. Tab to Suspend, Rename, Time zone, Activate and Resume; Enter or Space; Tab 14 times; Esc. In Rename: typed a name, Tab to Save, Enter | Dialogs open with focus inside, Esc closes them, the name is saved | K1 fixed |
| 0058 Entity switcher | Tab, Enter, Down, Down, End, Home, Esc; Down opens; Tab closes. With unsaved work: Enter on another entity, Tab, Esc | Menu moves and wraps, Esc returns focus to the button. "Stay" has focus first; Esc keeps the work | none |
| 0059 Notifications (IT, business) | Walked. Change, Space, Esc, Enter; walked the sheet; Space on the switch; Enter on Save | Saved once; order is switch, how often, tell after, tell again, limit, Cancel, Save | K1, K2 fixed |
| 0059 Approval page | Walked. Reason chosen by typing, Tab to Approve, Tab to Decline, Enter; Tab to Open the case | Decline is only reachable with a reason; nothing is decided by opening | none |
| 0061 New case, case fields | Walked: loan number, list, amount, date, address parts, several values, sensitivity, priority, documents. Typed values; Enter on Add another, typed, Tab to Remove, Enter. Arrows on the capture tabs | All kinds take their value | K3, K4, K5 fixed |
| 0061 Admin fields | Walked the screen and the editor. Enter on Edit, Esc; Space on a switch; Enter on Apply; Move down twice with Enter | Focus stays on the field's own buttons | K1 fixed |
| 0062 Catalog and packs | Walked. Space on a pack; a locked item: Space. Fine-tune opened with Enter; arrows in Send to review | The pack switches; a locked item is read and does not change | K4 fixed |
| 0071 Reject (panel, Review) | Tab to Reject case, Enter, Esc; Space; walked the dialog; Down on the reason; Enter on Reject | Esc returns focus; Reject needs a reason; sent once | none |
| 0073 Priority (case, source) | Walked the case panel. Up or Down on the list | Saved at once, announced by a message, focus stays on the list | K6 fixed |
| 0074 Page to check | Walked. Tab to the page in the queue, Space on the confirmation, Enter to finish. A mark (drawn with the mouse): Enter on Remove | Finished without a mark; only one page needs a pointer if it has something to mark | K7 fixed; drawing: known exception |
| 0076 Bulk approve | Walked. Space on two cases; Approve selected, Enter, Esc, Enter; walked the dialog; Enter on Approve 2; Tab to the code of the one that stayed | The person's own case is not a stop; its reason is text on the row. Result announced | Afterwards no control has focus (Approve selected is off); Tab goes on from there. Not fixed, listed |
| 0078 Queue, admin card | Walked. The table is a stop (it scrolls with the arrows). Enter and Space on How is this worked out? | Opens and closes, focus stays | none |
| 0078 Queue, Reports | Walked | | K2 fixed (breakdown table) |
| 0079 Bulk sources | Space on Migration; Up in a time; Enter on Save times; Space to end, Esc, Space, Enter on End migration | Saved and ended; ending asks first; Esc returns focus to the switch | K6 fixed. After Save times the button is gone; Tab goes on from there. Not fixed, listed |
| 0081 Reports tabs | Right, Left, Home, End on the tabs; arrows and End on Period; an own date typed; Tab to the filters. Overview and Daily walked. Enter on a tile's link, then on a code | One stop for the tabs and one for the period. The link goes to the reasons; the code opens the guide in a new tab | K4 fixed |
| 0082 Error code guide | Walked, in the app and without a session. Typed a code, Enter on Clear search, Enter on an area, Tab, Enter and Space on an entry | The asked entry has focus; after an area link Tab goes on in that area | K8 fixed |

## Problems found

| # | Problem | Fix |
|---|---|---|
| K1 | Entities, Notifications and Case fields: after Esc, Cancel or saving, focus was lost (the sheet was taken off the page while open) | The sheet is closed first, so the browser gives focus back |
| K2 | Last messages and the Reports breakdown table scroll sideways on a narrow screen and could not be reached | They are a Tab stop with a name |
| K3 | Add another: focus stayed on the button; Remove: focus was lost | The new value has focus; after Remove, the value in its place |
| K4 | Period (Reports), Send to review, Type (Audit trail), Show (Support) and the New case tabs said radio or tab but had no arrow keys | One Tab stop each; arrows, Home and End |
| K5 | A field marked wrong had a 1 px ring in focus | 2 px |
| K6 | Priority and Migration were switched off while saving, so focus was lost | Focus stays; a second change while saving is ignored |
| K7 | Remove on a mark: focus was lost | Focus goes to the confirmation |
| K8 | Clear search: focus was lost | Focus goes to the search box |
