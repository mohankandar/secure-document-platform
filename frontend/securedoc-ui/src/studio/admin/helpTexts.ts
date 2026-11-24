/**
 * UI task U026 part B: the "?" help notes on the Admin settings screens, all in one place so they are easy to review
 * and, later, to translate. Each note is one to three plain sentences: what the setting does, its default, and what
 * changing it affects. No internal names or codes. Shown only on Admin settings screens (not on case screens,
 * Reports or Review).
 *
 * `name` is the setting's name as the "?" button says it: "About <name>".
 */
export interface HelpText { name: string; text: string }

export const HELP = {
  // Admin › Redaction & retention
  sensitiveData: {
    name: 'Sensitive data',
    text: 'Chooses which kinds of personal data LexaVault finds and blacks out, and when a person checks a finding first. All four kinds are on by default. A change applies to new cases only; cases already processed keep the result they had.',
  },
  presets: {
    name: 'How much a person checks',
    text: 'Strict, Balanced and Light set how many findings a person checks before they are redacted. Balanced is the default: findings below 80% confidence go to a person. Your choice is part of your compliance record, and every change is recorded in the audit trail.',
  },
  fineTune: {
    name: 'Fine-tune by hand',
    text: 'Sets the review rule and the confidence level yourself instead of using a preset. Below the level you set, a finding always goes to a person; nothing is skipped. Any value that matches no preset shows as Custom.',
  },
  documentIntelligence: {
    name: 'Document intelligence',
    text: 'Lets AI read the case fields, such as the loan number, from the documents. The default is Off: staff type them. Observe records suggestions to compare without filling anything in; On fills them in for staff to confirm.',
  },
  keepingAndDeleting: {
    name: 'Keeping and deleting',
    text: 'How long LexaVault keeps each copy of a case before deleting it for good, and whether a supervisor approves delivery. Shorter times keep less personal data; this is part of your retention policy for compliance, and every change is recorded in the audit trail.',
  },
  keepOriginals: {
    name: 'Keep originals for',
    text: 'How long the documents as received are kept, unredacted. The default is 24 hours. After this time a case can no longer be processed again, because there is nothing left to process.',
  },
  keepRedacted: {
    name: 'Keep redacted copies for',
    text: 'How long the redacted copy is kept after it is made, so it can be sent again if needed. The default is 24 hours, and the most allowed is shown under the field.',
  },
  keepStopped: {
    name: 'Keep stopped cases for',
    text: 'How long a case that stopped before delivery is kept, so someone can fix and finish it. The default is 7 days. After that it is deleted.',
  },
  requireApproval: {
    name: "Require a supervisor's approval",
    text: 'When on, every case waits in Approval until a supervisor signs off before it is delivered. It is off by default. Turning it on adds a step, and a record of who approved, to every case.',
  },
  reportTargets: {
    name: 'Report targets',
    text: 'The goals that Reports measures your work against: how fast cases are delivered, how many need no review, and how fast reviews are picked up. The defaults are 4 hours for 95% of cases, 90% needing no review, and 1 hour for 80% of reviews. Changing them changes only how Reports judges the work, not the work itself.',
  },
  minutesPerPage: {
    name: 'Minutes per page',
    text: 'How long a person would take to check one page by hand. Reports and the compliance report use it to estimate the review time LexaVault saved. The default is 1 minute, kept low so the estimate never overclaims; changing it is recorded in the audit trail.',
  },
  reportBreakdown: {
    name: 'Break reports down by',
    text: 'Lets Reports split its numbers by a case field, such as the property state. Only fields with a short list of allowed values can be used. It is off for every field by default.',
  },

  // Admin › Case fields
  caseFields: {
    name: 'Case fields',
    text: 'The details staff enter or confirm for each case, such as the loan number and the borrower name. The main ID names the case everywhere in LexaVault. The order here is the order staff see.',
  },
  columnsOnCases: {
    name: 'Columns on Cases',
    text: 'Which columns people may show on the Cases screen, and which are shown to new users. The main ID is always shown. Changing it does not change any case.',
  },

  // Admin › Integrations
  receiving: {
    name: 'Receiving documents',
    text: 'The systems, folders and scanners that bring documents into LexaVault. Each one has its own sign-in details and status. Switching one off stops new documents coming in from it.',
  },
  sendingToDocumentStore: {
    name: 'Sending to the document store',
    text: 'Where the full redacted case goes when it is delivered, and the sign-in details LexaVault uses there. Test a change before you save it, so delivery is not interrupted.',
  },
  sendingByBarcode: {
    name: 'Sending by barcode',
    text: 'Also sends chosen pages, as separate files, to other places, picked by a barcode or text marker on the page. The full redacted case still goes to the document store. Nothing is sent this way until a rule names a place.',
  },
  allConnections: {
    name: 'All connections',
    text: 'Every connection, receiving and sending, in one list. Use the other views to add a connection of one kind.',
  },
  routingRules: {
    name: 'Rules',
    text: 'Each rule names a barcode or text marker, where on the page to look for it, and where to send the pages that carry it. A page that matches no rule is sent only with the full case. A rule can be switched off without removing it.',
  },

  // Admin › Sign-in
  signIn: {
    name: 'Sign-in',
    text: 'The login system your people use to sign in to LexaVault. Test sign-in checks that it answers; it changes nothing.',
  },

  signInDraft: {
    name: 'Change the sign-in',
    text: 'A draft is a copy of the connection in use. Change it, test it, then switch. The test reads your login system\'s published details, and checks a token if you paste one. A change of login system, provider ID or audience also needs the server\'s settings, so it is switched at a restart.',
  },

  // Admin › Platform operations
  branding: {
    name: 'Branding',
    text: 'The product name, organization name, colours and logos people see in LexaVault. By default every customer sees the installation\'s branding; a customer can be given its own. Status colours for success, warning and error never change.',
  },
  storage: {
    name: 'Processing storage',
    text: 'Where LexaVault keeps documents while it works on them. One storage is the default for every customer; a customer can be given its own. A new storage must work from every server before it can be used.',
  },
  telemetry: {
    name: 'Usage data',
    text: 'Whether LexaVault sends counts about how the product is used, so it can be improved. Only the items listed are sent, never document content or who did what. It is set when LexaVault is installed and cannot be changed here.',
  },
  entities: {
    name: 'Entities',
    text: 'The entities your licence gives you. A new one is activated and named here once. A suspended one lets nobody in and takes nothing new in until it is resumed; its cases are finished and nothing is deleted. You see the entities you are a Business Admin of.',
  },
  licensing: {
    name: 'Licensing',
    text: 'Your organisation\'s licence: what it covers, when it ends, and how many pages this entity used this month. It is stored by IT with the licence command and cannot be changed here. When it has ended and the grace period is over, new cases are not taken in; everything else keeps working.',
  },
} as const satisfies Record<string, HelpText>;

export type HelpKey = keyof typeof HELP;
