# The role mapping file

The role mapping file says which login-system groups get which LexaVault role in which entity. It is a CSV file with one row per grant: `entity_id,entity_name,group,role`. Start from [role-mapping-template.csv](role-mapping-template.csv); its comments explain each column.

## Fill it in

- Review the file with the customer's IT, line by line. Write who reviewed it, and when, in a `#` comment at the top.
- Write group names exactly as the login system sends them: same case, no leading slash, no spaces at either end. Excel may save the file as "CSV UTF-8". That is fine.
- An entity appears only through its rows. Loading a file makes the grants of each entity it names exactly equal to its rows, and does not touch other entities. To remove one grant, delete its row and load the file again. No file can remove every grant of an entity by leaving its rows out.

## Check it, then load it

Run these on the LexaVault server, with the same database settings as the server. The server can stay running.

```powershell
java -jar securedoc-api.jar --lexavault.command=load-role-mappings --file=acme-roles.csv --dry-run
java -jar securedoc-api.jar --lexavault.command=load-role-mappings --file=acme-roles.csv
```

- `--dry-run` checks the file and prints, for each entity, the grants to add, to remove and unchanged. It writes nothing. Run it with the customer before the real load.
- The real load writes everything in one transaction, or nothing. It records one `ROLE_MAPPING_LOADED` audit record per changed entity, with the counts, the file's SHA-256 and name, and the operating-system user. Loading the same file again prints `No changes.`
- Exit code 0 means the file was loaded, or there was nothing to change. 1 means the file was refused. 2 means the command line was wrong or the file could not be read. 3 means the command could not start. Nothing is written unless the code is 0. Changes take effect at the next sign-in or request.

## When the file is refused

Every error is printed with its line number, and nothing is written. Correct the file and run the command again.

| Message (examples) | What to do |
|---|---|
| `unknown role "DOCUMENT_PROCESOR"` | Use one of the five roles in the template, spelled exactly. |
| `role "reader" must be written exactly as "REPORT_VIEWER"` | Write the role in capitals, by its full name. |
| `role "PLATFORM_OPERATIONS_ADMIN" is the platform role` | Platform access is set for the installation, never in this file. |
| `entity ... does not exist in this installation`, `is not active` | Check the id with the platform team. The entity must be ACTIVE. |
| `entity_name "Acme" does not match the entity's name "Acme Lending"` | The name guards against a wrong id. Copy both from LexaVault. |
| `has no active login system` | Activate the entity's login-system record first, so its grants can apply. |
| `contains a non-breaking space`, `starts with a space`, `contains a control character` | Retype the value. Invisible differences make a group that never matches. |
| `the same row as line 4`, `is named "..." here but "..." on line 3` | Remove the duplicate, or use one name per entity. |
| `expected 4 values`, `is empty`, `quotes are not closed`, `contains a line break` | Fix the row's columns. Quote a value that contains a comma. |
| `the first line must be exactly entity_id,entity_name,group,role`, `has a header but no rows` | Keep the header line as in the template, and give at least one row. |
| `this file is format "..."` | Keep the line `# ... Format: lexavault-role-mapping/1`, or remove it. |
| `the file is not UTF-8 text` | Save the file as "CSV UTF-8". |
