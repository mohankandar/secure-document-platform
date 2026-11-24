# Notifications and approval links: install guide for IT

LexaVault sends short e-mails: what happened, when, how many, and a link into the app. A message never holds document
content, a case number, a name from a document, a redacted value, an address or a secret. Nothing is sent until you set
the app's address and an IT Admin switches e-mail on for an entity. It needs the `notif_` tables: run the schema step
(`SCHEMA_STEP.md`) before starting this release; without them the app does not start (`TABLES_MISSING`).

## 1. What the installation sets

| Setting | Default | Meaning |
|---|---|---|
| `securedoc.notifications.app-base-url` (`SECUREDOC_NOTIFICATIONS_APP_BASE_URL`) | none | Where people reach the app, `https://...` (http only for localhost). Links start with it. Without it nothing is sent. |
| `securedoc.notifications.people-seen-within` | `14d` | Who signed in longer ago gets no messages (1 to 90 days). |
| `securedoc.notifications.mail-ports` | `25,465,587,2525` | The ports an IT Admin may choose. |
| `securedoc.notifications.microsoft-authority` | `https://login.microsoftonline.com` | Another only for a national cloud. |
| `securedoc.secrets.directory` (`SECUREDOC_SECRETS_DIRECTORY`) | none | The folder of `file:` secrets. |
| `platform.notifications.approvals.link-key-secret` | none | The reference of the key that signs approval links. Not set: no links; delivery is approved on the case. |
| `platform.notifications.enabled` | `true` | `false` switches all of it off. |

The other `platform.notifications.*` settings (retries, retention, `sender.poll-interval`) are platform-core's. Its
`mail.*`, `teams.*` and `slack.*` settings are **not** used: each entity has a mail account of its own.

## 2. Secrets

A secret is never typed into LexaVault. You store it as an environment variable of the app (`env:NAME`) or as a file in
`securedoc.secrets.directory` (`file:NAME`, for example a mounted container secret), and give its name. Only these names
are read:

| Name starts with | For |
|---|---|
| `LEXAVAULT_SECRET_INSTALL_` | The installation: the link key, the proxy password. |
| `LEXAVAULT_SECRET_<entity id, 32 characters>_` | One entity: its mail password, client secret, or certificate with its key. The screen shows the exact start. An entity can't name another entity's secrets, nor anything else. |

They are read at each use: replace one and no restart is needed for a file.

## 3. The mail account of an entity (Admin > Notifications, IT Admin)

- **Microsoft 365:** register the app and the mailbox as in platform-core's `docs/install/notifications.md` section 1a.
  Give the directory ID, the client ID, the sender mailbox, the kind of credential and the secret's name. The app server
  needs `login.microsoftonline.com:443` (Java's own proxy options are used there) and `smtp.office365.com:587` directly.
- **Your own Exchange server or relay:** server, port, security, and a user name with the secret's name, or none for a relay.
  A login is always encrypted (STARTTLS or TLS). Without encryption only a relay without a login, and only where
  `platform.outbound.allow-internal-addresses` is on. That setting is also needed when the server is on an internal
  address. Cloud metadata and link-local addresses are always refused. A certificate from your own authority:
  `platform.outbound.private-ca-file`.

"Send me a test message" goes to the address of the person who clicks it. The last 10 messages show their state and, when
one was not sent, the reason and its code.

## 4. Who gets messages

Roles come from your login system's groups and are known only when a person signs in. At each sign-in LexaVault writes down
the person's id, the entity, the roles and the e-mail address of the token (not when the login system says the address is
not verified). Nobody else is ever sent anything. The next sign-in replaces it, and it is removed after
`people-seen-within`. It gives no access.

## 5. Approval links

1. Make a key of at least 32 characters, for example `openssl rand -base64 48`.
2. Store it as `LEXAVAULT_SECRET_INSTALL_LINK_KEY`.
3. Set `platform.notifications.approvals.link-key-secret=env:LEXAVAULT_SECRET_INSTALL_LINK_KEY`.

A case that waits for delivery approval is sent to the entity's Supervisors (not to the person who submitted it), each with
their own links. A link works for that person, in that entity, for that version of the case, once, for 24 hours. Opening it
decides nothing; a second click does, and runs the same approval as on the case. Every use and refusal is in the audit trail.
Keep `/approvals/link/` out of the access logs of a web server in front of the app, or make them short-lived: the address
holds the link until the page opens (it is worth nothing without that person's sign-in).

## 6. Outbound proxy with sign-in

`platform.outbound.proxy.user` and `.proxy.password-secret` (a `LEXAVAULT_SECRET_INSTALL_` name) are allowed now. They are
used by the test sign-in. Mail, and Microsoft's sign-in for mail, do not use this proxy.
