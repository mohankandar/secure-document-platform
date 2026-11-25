// Task 0085, step 1: how well the local model tells the type of a document from the text of its beginning. All documents are
// invented here, in three sets with other layouts: A (the prompt is tuned on it), B (held out, but written before the
// prompt) and C (written after the prompt was frozen). Numbers and types only.
//   SET=A|B|C [OLLAMA=http://localhost:11434] [MODEL=qwen3.5:9b] [SAMPLES=C:/dev/benchmark-samples/public] node measure.mjs
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SET = process.env.SET ?? 'A';
const here = dirname(fileURLToPath(import.meta.url));
const PROMPT = readFileSync(join(here, 'prompt-v1.txt'), 'utf8').replace(/\r\n/g, '\n').trim();
const TYPES = ['LOAN_APPLICATION', 'DEED', 'W2', 'PAY_STUB', 'BANK_STATEMENT', 'CREDIT_REPORT', 'PROMISSORY_NOTE', 'CLOSING_DISCLOSURE',
  'COMPLAINT', 'MOTION', 'AFFIDAVIT', 'COURT_ORDER', 'OTHER'];
let seed = { A: 85, B: 8500, C: 850000 }[SET];
const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
const pick = (a) => a[Math.floor(rnd() * a.length)];
const n = (a, b) => a + Math.floor(rnd() * (b - a + 1));
const usd = (a, b) => `$${n(a, b).toLocaleString('en-US')}.${String(n(0, 99)).padStart(2, '0')}`;
const who = () => `${pick(['Linnea', 'Garrick', 'Isidor', 'Maren', 'Tobin', 'Odile', 'Perrin', 'Salka'])} ${pick(['Thistlewood', 'Wexcombe', 'Quillfeather', 'Brackwater', 'Hollin', 'Vantongeren', 'Ashgrove'])}`;
const day = () => `${pick(['January', 'March', 'May', 'August', 'October'])} ${n(1, 28)}, 2026`;
const place = () => `${n(100, 9999)} ${pick(['Fictional Lane', 'Sample Street', 'Example Avenue'])}, Exampleville`;
const firm = () => `${pick(['Northwind', 'Larkspur', 'Meridian', 'Cobalt'])} ${pick(['Supply', 'Foods', 'Logistics', 'Dental'])} ${pick(['LLC', 'Inc.', 'Co.'])}`;

const A = {
  LOAN_APPLICATION: [() => `UNIFORM RESIDENTIAL LOAN APPLICATION\nSection 1: Borrower Information\nName: ${who()}  Dependents: ${n(0, 3)}\nCurrent Address: ${place()}  Own / Rent\nEmployer: ${firm()}  Gross monthly income: ${usd(3000, 12000)}\nSection 4: Loan and Property\nLoan amount: ${usd(90000, 400000)}  Loan purpose: Purchase`,
    () => `Request for mortgage credit\nApplicant ${who()}, co-applicant ${who()}\nProperty to be financed: ${place()}\nAmount requested ${usd(90000, 400000)}, term 360 months\nAssets: checking ${usd(900, 9000)}. Monthly income ${usd(3000, 9000)}. Declarations: are there outstanding judgments against you? No`],
  DEED: [() => `WARRANTY DEED\nTHIS INDENTURE made ${day()} between ${who()}, Grantor, and ${who()}, Grantee. Grantor does hereby grant, bargain, sell and convey unto Grantee the following described real property: Lot ${n(1, 40)}, Block ${n(1, 9)}, Sample Acres.`,
    () => `Recording requested by and when recorded mail to: ${who()}\nFor valuable consideration, ${who()} remises, releases and quitclaims to ${who()} all right, title and interest in Lot ${n(1, 99)} of Tract ${n(100, 999)}, Example County.`],
  W2: [() => `Form W-2 Wage and Tax Statement 2025\nEmployer: ${firm()}\n1 Wages, tips, other compensation ${usd(20000, 90000)}\n2 Federal income tax withheld ${usd(1000, 9000)}\n3 Social security wages ${usd(20000, 90000)}\nEmployee: ${who()}`,
    () => `Copy B - To be filed with employee's federal tax return\nb Employer identification number (EIN)\nc ${firm()}\ne ${who()}\n1 ${usd(20000, 90000)}  2 ${usd(900, 9000)}  5 Medicare wages and tips ${usd(20000, 90000)}  12a D`],
  PAY_STUB: [() => `${firm()}\nEARNINGS STATEMENT\nPay period 10/01/2026 - 10/15/2026  Pay date 10/20/2026\nEmployee ${who()}\nRegular 80.00 hrs  ${usd(1200, 4000)}  YTD ${usd(20000, 60000)}\nFederal tax ${usd(90, 400)}  Net pay ${usd(900, 3000)}`,
    () => `Direct deposit advice, non-negotiable\n${who()}\nHours and earnings: regular ${n(60, 80)} at $${n(15, 45)}.00; overtime ${n(0, 9)}\nTaxes and deductions this period and year to date\nNet pay deposited ${usd(900, 3000)}`],
  BANK_STATEMENT: [() => `SAMPLE COMMUNITY BANK\nAccount statement 10/01/2026 to 10/31/2026\n${who()}\nBeginning balance ${usd(500, 9000)}\nDeposits and credits ${usd(500, 9000)}\nWithdrawals and debits ${usd(500, 9000)}\nEnding balance ${usd(500, 9000)}\n10/03 Coffee shop ${usd(3, 9)}`,
    () => `Checking summary for ${who()}\nYour balance on October 1 was ${usd(500, 5000)}\n10/02 payroll deposit ${usd(900, 3000)}\n10/05 card purchase grocery store ${usd(20, 200)}\n10/09 ATM withdrawal $60.00\nAverage daily balance ${usd(500, 5000)}`],
  CREDIT_REPORT: [() => `CONSUMER CREDIT REPORT\nPrepared for ${who()}\nFICO score ${n(580, 810)}\nTrade lines: ${n(3, 12)}  Inquiries: ${n(0, 6)}  Public records: none\nRevolving account opened 05/2019, limit ${usd(1000, 9000)}, 30 days late: 0`,
    () => `Merged infile, three bureaus\n${who()}\nScores ${n(580, 810)} / ${n(580, 810)} / ${n(580, 810)}\nAccounts: auto loan, balance ${usd(2000, 20000)}, paid as agreed; collections 0\nInquiries in the last 120 days: ${n(0, 4)}`],
  PROMISSORY_NOTE: [() => `PROMISSORY NOTE\n${day()}\nFor value received, the undersigned promises to pay to the order of Sample Lending the principal sum of ${usd(50000, 300000)} with interest at ${n(4, 8)}.25% per annum, in monthly payments of ${usd(400, 2500)}.`,
    () => `1. BORROWER'S PROMISE TO PAY. In return for a loan that I have received, I promise to pay U.S. ${usd(50000, 300000)} (this amount is called "Principal"), plus interest, to the order of the Lender.\n2. INTEREST. 3. PAYMENTS. I will pay on the 1st day of each month.`],
  CLOSING_DISCLOSURE: [() => `CLOSING DISCLOSURE\nClosing Information: Date issued ${day()}\nLoan Terms: Loan amount ${usd(90000, 400000)}; interest rate ${n(4, 8)}.125%\nProjected payments. Costs at closing: closing costs ${usd(3000, 12000)}; cash to close ${usd(9000, 60000)}`,
    () => `Final costs for ${who()}\nA. Origination charges ${usd(500, 2000)}  B. Services borrower did not shop for  E. Taxes and other government fees  F. Prepaids\nCalculating cash to close: total closing costs ${usd(3000, 12000)}; down payment ${usd(9000, 60000)}`],
  COMPLAINT: [() => `IN THE DISTRICT COURT OF EXAMPLE COUNTY\n${who()}, Plaintiff, v. ${firm()}, Defendant.\nCOMPLAINT AND DEMAND FOR JURY TRIAL\n1. Plaintiff resides in Example County. 2. This Court has jurisdiction. COUNT I: NEGLIGENCE. WHEREFORE Plaintiff demands judgment.`,
    () => `${who()} v. ${who()}, Case No. ${n(20, 26)}-${n(100, 999)}\nPlaintiff alleges as follows: Defendant sold the property without disclosing the flooding. First cause of action, breach of contract. Plaintiff prays for damages of ${usd(9000, 90000)}.`],
  MOTION: [() => `UNITED STATES DISTRICT COURT\n${who()} v. ${firm()}\nDEFENDANT'S MOTION TO DISMISS\nDefendant moves this Court under Rule 12(b)(6) for an order dismissing the action. A memorandum in support is attached.`,
    () => `Case No. ${n(20, 26)}-${n(100, 999)}\nPlaintiff ${who()}, through counsel, respectfully requests that the Court compel production of the records asked for on ${day()}. Grounds: the responses are overdue. Plaintiff moves for an order and for fees.`],
  AFFIDAVIT: [() => `AFFIDAVIT OF ${who().toUpperCase()}\nState of Sample, County of Example, ss.\nI, being duly sworn, depose and say: 1. I am over eighteen. 2. I make this affidavit on personal knowledge. Subscribed and sworn to before me, Notary Public.`,
    () => `I, ${who()}, declare under penalty of perjury that the following is true: On ${day()} I was at ${place()} and saw the delivery. I signed this statement before a notary.`],
  COURT_ORDER: [() => `IN THE DISTRICT COURT\n${who()} v. ${who()}\nORDER GRANTING MOTION TO COMPEL\nThis matter came before the Court. Having considered the papers, IT IS HEREBY ORDERED that the motion is GRANTED. SO ORDERED. Judge`,
    () => `Case No. ${n(20, 26)}-${n(100, 999)}\nUpon review of the stipulation, the Court finds good cause. It is ordered, adjudged and decreed that the hearing is continued to ${day()}. District Judge`],
  OTHER: [() => `Dear ${who()},\nThank you for your visit to our office. Your next cleaning is on ${day()}. Please call us if you need another time.\n${firm()}`,
    () => `INVOICE ${n(1000, 9999)}\n${firm()}\nBill to ${who()}\n${n(2, 9)} boxes of paper ${usd(20, 90)}\nTotal due ${usd(20, 400)} within 30 days`,
    () => `Minutes of the garden club, ${day()}\nPresent: ${who()}, ${who()}. The spring sale was discussed. Next meeting in May.`],
};
const B = {
  LOAN_APPLICATION: [() => `To be completed by the Lender: Lender Loan No. ${n(10000, 99999)}\nI. TYPE OF MORTGAGE AND TERMS OF LOAN  Conventional  FHA  VA\nAmount ${usd(90000, 400000)}  No. of months 360\nIII. BORROWER INFORMATION ${who()}  Yrs. school ${n(12, 18)}  Present address ${place()}\nVI. ASSETS AND LIABILITIES  VIII. DECLARATIONS`,
    () => `Apply for a home loan - page 1 of 5\nTell us about you: ${who()}\nWhere do you live? ${place()}  How long? ${n(1, 20)} years\nWhere do you work? ${firm()}  What do you earn a month? ${usd(3000, 9000)}\nHow much would you like to borrow? ${usd(90000, 300000)}`,
    () => `Borrower: ${who()}  Co-borrower: ${who()}\nEmployment history, two years. ${firm()}, position held ${n(1, 9)} years\nMonthly housing expense: rent ${usd(700, 2400)}\nSubject property ${place()}, purchase price ${usd(120000, 500000)}, source of down payment: savings\nAcknowledgment: the information provided in this application is true`],
  DEED: [() => `DEED OF TRUST\nTrustor ${who()} irrevocably grants and conveys to Trustee, in trust, with power of sale, the property in Example County known as ${place()}. Beneficiary: Sample Lending.`,
    () => `Know all persons by these presents, that ${who()}, unmarried, for ten dollars and other good and valuable consideration, conveys and warrants to ${who()} and heirs forever the land: beginning at an iron pin, thence north ${n(10, 80)} degrees east ${n(100, 600)} feet to the point of beginning.`,
    () => `SPECIAL WARRANTY - Register of Deeds Book ${n(100, 999)} Page ${n(1, 900)}\nParty of the first part ${firm()}, party of the second part ${who()}. To have and to hold the premises, with the appurtenances, in fee simple. Revenue stamps ${usd(50, 900)}`],
  W2: [() => `2025 wages and tax withheld, copy C for employee's records\n${firm()}   ${who()}\nBox 1 ${usd(20000, 90000)}  Box 2 ${usd(900, 9000)}  Box 3 ${usd(20000, 90000)}  Box 4 ${usd(900, 5000)}  Box 16 State wages ${usd(20000, 90000)}  Box 17 State income tax ${usd(300, 3000)}\nOMB No. 1545-0008`,
    () => `a Employee's social security number   OMB No. 1545-0008\nWages, tips, other comp. ${usd(20000, 90000)}   Social security tax withheld ${usd(900, 5000)}\n13 Statutory employee / Retirement plan / Third-party sick pay\n${who()}, ${place()}`],
  PAY_STUB: [() => `Payroll check no. ${n(10000, 99999)}\n${firm()}   ${who()}   Department ${n(10, 90)}\nGross ${usd(1200, 4000)}   FICA ${usd(70, 300)}   401(k) ${usd(50, 300)}   Health ${usd(30, 200)}\nNet ${usd(800, 3000)}\nVacation hours left ${n(4, 80)}`,
    () => `Wage slip, week ending ${day()}\n${who()}, badge ${n(100, 999)}\nMon 8.0 Tue 8.0 Wed 7.5 Thu 8.0 Fri 8.0\nRate $${n(15, 40)}.50  Gross ${usd(500, 1600)}  Less tax ${usd(50, 300)}  To be paid ${usd(400, 1300)}  Year to date ${usd(9000, 50000)}`],
  BANK_STATEMENT: [() => `Page 1 of 4   Savings ending ${n(1000, 9999)}\n${who()}, ${place()}\nPrevious balance ${usd(500, 9000)}   Interest paid ${usd(1, 30)}   New balance ${usd(500, 9000)}\nDate Description Amount Balance\n11/02 Transfer in   11/06 Check 1021   11/11 Service charge`,
    () => `Business account activity, November\n${firm()}\nOpening ledger balance ${usd(5000, 90000)}; ${n(5, 40)} credits; ${n(5, 40)} debits\n11/03 Wire in ${usd(900, 9000)}  11/04 Card settlement ${usd(90, 900)}  11/09 Tax payment\nClosing ledger balance ${usd(5000, 90000)}`],
  CREDIT_REPORT: [() => `File of ${who()}\nSummary: total accounts ${n(3, 20)}, open ${n(1, 9)}, negative ${n(0, 3)}\nVantage ${n(580, 810)}\nAccount history: OK OK OK 30 OK OK\nRequests viewed by others: Sample Motors, ${day()}`,
    () => `Residential mortgage credit information\nApplicant ${who()}  Repositories: all\nSCORE INFORMATION ${n(580, 810)}, factors: proportion of balances to limits too high\nTRADELINES  ECOA B  Rev  High credit ${usd(1000, 9000)}  Past due 0  Mop R1`],
  PROMISSORY_NOTE: [() => `FIXED RATE NOTE\n${day()}   ${place()}\nBorrower's failure to pay as required: late charge for overdue payments ${n(3, 5)}%. Borrower's right to prepay. Obligations of persons under this Note. I, ${who()}, promise to pay ${usd(50000, 300000)}.`,
    () => `On demand, after date, I promise to pay to the order of ${who()} ${usd(5000, 50000)} with interest at the rate of ${n(4, 9)} percent a year until paid. Presentment and notice of dishonor are waived. Signed ${who()}, maker.`],
  CLOSING_DISCLOSURE: [() => `A. Settlement Statement (HUD-1)\nBorrower ${who()}  Seller ${who()}  Settlement agent Sample Title\nJ. Summary of borrower's transaction: 101 Contract sales price ${usd(90000, 400000)}; 103 Settlement charges to borrower ${usd(3000, 12000)}; 303 Cash from borrower`,
    () => `This form is a statement of final loan terms and closing costs. Compare this document with your Loan Estimate.\n${who()}\nMonthly principal & interest ${usd(500, 2500)}   Prepayment penalty NO   Balloon payment NO   Estimated taxes, insurance & assessments ${usd(200, 800)}`],
  COMPLAINT: [() => `SUPERIOR COURT, SAMPLE STATE\n${firm()}, Plaintiff, against ${who()}, Defendant\nPETITION FOR DAMAGES\nComes now the plaintiff and for its cause of action states: that on ${day()} defendant failed to pay; that the sum of ${usd(900, 90000)} is owing.`,
    () => `Parties. ${who()} is a resident of Sample. ${firm()} is a corporation. Facts. On ${day()} plaintiff slipped on a wet floor. Claims for relief: negligence; premises liability. Jury demanded on all issues so triable.`],
  MOTION: [() => `${who()} v. ${who()}\nNOTICE OF MOTION AND MOTION FOR SUMMARY JUDGMENT\nPlease take notice that on ${day()}, or as soon as counsel may be heard, the moving party will ask for judgment as a matter of law, there being no genuine dispute of material fact.`,
    () => `Petitioner's request to continue the trial date\nNow comes petitioner and asks the Court to move the trial set for ${day()}. In support: counsel is ill; opposing counsel does not object. Wherefore petitioner asks that this request be granted.`],
  AFFIDAVIT: [() => `SWORN STATEMENT\n${who()}, first being duly sworn on oath, states: I am the custodian of the records of ${firm()}. The attached pages are true copies. Further affiant sayeth naught. My commission expires ${day()}.`,
    () => `Declaration of ${who()}\n1. I have personal knowledge of the facts stated here and could testify to them. 2. I have lived at ${place()} since 2011. I declare under penalty of perjury under the laws of Sample that the foregoing is true and correct. Executed on ${day()}.`],
  COURT_ORDER: [() => `${who()} v. ${firm()}\nJUDGMENT\nThe jury having returned its verdict, judgment is entered for the plaintiff in the sum of ${usd(9000, 90000)} with costs. Entered ${day()}. Clerk / Judge`,
    () => `SCHEDULING ORDER\nDiscovery shall be completed by ${day()}. Dispositive motions shall be filed by ${day()}. Failure to comply may lead to sanctions. The clerk is directed to send a copy to counsel. BY THE COURT`],
  OTHER: [() => `Recipe for lentil soup\nTwo cups of lentils, one onion, three carrots. Simmer for forty minutes. Serves ${n(4, 8)}. From the kitchen of ${who()}`,
    () => `Electric bill\n${who()}, ${place()}\nMeter reading ${n(10000, 99999)}, used ${n(200, 900)} kWh\nAmount due ${usd(40, 250)} by ${day()}`,
    () => `Homeowners policy declarations\nInsured ${who()}  Dwelling ${usd(90000, 400000)}  Deductible $1,000  Premium ${usd(700, 2500)}  Agent ${firm()}`,
    () => `LEASE\n${who()} (landlord) lets to ${who()} (tenant) the apartment at ${place()} for twelve months at ${usd(700, 2400)} a month. Security deposit one month.`,
    () => `POWER OF ATTORNEY\nI, ${who()}, appoint ${who()} as my agent to act for me in banking and real estate matters.`],
};
// Set C was written after the prompt was frozen: it is the one that says how well an unseen layout is recognized.
const C = {
  LOAN_APPLICATION: [() => `HELOC intake sheet\n${who()} wants a line of ${usd(20000, 90000)} against ${place()}\nFirst lien balance ${usd(40000, 200000)}; value stated ${usd(150000, 400000)}\nJob: ${firm()}, ${usd(3000, 9000)} a month. Car payment ${usd(150, 600)}. Bankruptcies: none. I authorize a credit check.`,
    () => `Auto finance - customer statement\nBuyer ${who()}, rents at ${place()} for ${usd(600, 1900)}\nEmployer ${firm()}; time on job ${n(1, 12)} yrs; net monthly ${usd(1800, 6000)}\nAmount to finance ${usd(9000, 40000)}; references: ${who()}. I certify these statements and ask for credit.`],
  DEED: [() => `This conveyance, dated ${day()}: the personal representative of the estate of ${who()} sells and releases to ${who()} Unit ${n(1, 90)} of Sample Towers with its share of the common elements. Filed for record ${n(1, 12)}:20 p.m.`,
    () => `Mortgage. ${who()}, mortgagor, mortgages, grants and conveys to Sample Lending the premises at ${place()} to secure a debt of ${usd(50000, 300000)}. Uniform covenants. Mortgagor shall keep the improvements insured.`],
  W2: [() => `Corrected wage and tax statement (W-2c)\n${firm()}\nEmployee ${who()}\nPreviously reported: box 1 ${usd(20000, 90000)}   Correct information: box 1 ${usd(20000, 90000)}`,
    () => `Employee reference copy 2024\nW2 and earnings summary\n${who()}\nGross pay ${usd(20000, 90000)} less 401(k) equals box 1 of W-2 ${usd(20000, 90000)}; state wages box 16; local box 18`],
  PAY_STUB: [() => `Leave and Earnings Statement\n${who()}  grade E${n(3, 7)}\nEntitlements: base pay ${usd(2000, 5000)}, BAS ${usd(300, 500)}\nDeductions: federal taxes, SGLI\nMid-month pay ${usd(900, 2000)}  EOM pay ${usd(900, 2000)}`,
    () => `Remittance to ${who()}\nCommission Oct 1-15: ${usd(400, 3000)}   Draw: ${usd(200, 900)}\nWithholding: fed ${usd(40, 300)}, state ${usd(10, 90)}\nAmount of this check ${usd(400, 2500)}   Yearly total so far ${usd(9000, 60000)}`],
  BANK_STATEMENT: [() => `Card services. Statement closing date ${day()}\n${who()}\nPrevious balance ${usd(100, 3000)}  Payments ${usd(50, 900)}  Purchases ${usd(50, 900)}  New balance ${usd(100, 3000)}\nMinimum payment due $35.00`,
    () => `Money market, quarter to date\nHolder ${who()}\n07/31 balance forward ${usd(9000, 90000)}\n08/31 dividend credited ${usd(10, 200)}\n09/12 check 204 ${usd(100, 900)}\n09/30 balance ${usd(9000, 90000)}`],
  CREDIT_REPORT: [() => `Tenant screening result for ${who()}\nCredit score ${n(500, 800)}; accounts in collection ${n(0, 3)}; charge-offs ${n(0, 2)}; evictions none. Source: national bureau, pulled ${day()}`,
    () => `Subject: ${who()}\nDEROG COUNTERS 30/60/90: ${n(0, 3)}/0/0\nBUREAU: XP SCORE ${n(580, 810)} FACTORS 14, 10, 5\nSAMPLE BANK R1 ${usd(100, 5000)} / CAR CREDIT I1 ${usd(100, 9000)}`],
  PROMISSORY_NOTE: [() => `Student loan agreement to repay. I, ${who()}, will repay ${usd(3000, 30000)} and all interest in 120 installments beginning six months after I leave school. Default, acceleration.`,
    () => `Balloon note\nMaker: ${firm()}. Holder: ${who()}. Principal ${usd(50000, 500000)} due in full ${day()}. Interest only until then. Maker waives demand and protest.`],
  CLOSING_DISCLOSURE: [() => `Estimated and final figures side by side\nOrigination ${usd(500, 2000)} / ${usd(500, 2000)}; transfer taxes ${usd(200, 2000)}; owner's title ${usd(300, 1500)}; lender credits\nCash needed from ${who()} at signing table ${usd(9000, 60000)}`,
    () => `Seller's closing statement\nProperty ${place()}\nGross amount due to seller ${usd(90000, 400000)}; payoff of first mortgage ${usd(40000, 200000)}; commission ${usd(3000, 20000)}; net proceeds ${usd(20000, 200000)}\nEscrow officer ${who()}`],
  COMPLAINT: [() => `FAMILY COURT. Petition for dissolution of marriage. ${who()}, petitioner, states: the parties married on ${day()}; the marriage is irretrievably broken; petitioner asks for a division of property.`,
    () => `Statement of small claim\nI, ${who()}, sue ${firm()} for ${usd(200, 4000)} because the refrigerator delivered on ${day()} never worked and the store refuses a refund. Filed with the clerk.`],
  MOTION: [() => `Memorandum of points and authorities in support of excluding the expert\n${who()} v. ${firm()}\nThe testimony should be excluded because the method was never tested. The moving party therefore asks for relief in limine.`,
    () => `${firm()} v. ${who()}\nApplication for a temporary restraining order\nThe applicant will be harmed before notice can be given and asks the Court to restrain the sale set for ${day()}.`],
  AFFIDAVIT: [() => `Affidavit of service. ${who()}, a process server, swears: on ${day()} at 4 p.m. I handed the summons to ${who()} at ${place()}. Sworn before me.`,
    () => `Verification. I, ${who()}, have read the above answers; they are true to my knowledge and belief, under oath. Notarial seal of Sample.`],
  COURT_ORDER: [() => `Decree of divorce. The Court, having heard the evidence, decrees that the marriage of ${who()} and ${who()} is dissolved; that the house at ${place()} is awarded to the petitioner.`,
    () => `MINUTE ENTRY\n${who()} v. ${firm()}. The motion to continue is denied. Trial remains set for ${day()}. Counsel are to submit exhibits by noon the day before. ${who()}, Magistrate`,
    () => `OPINION AND ORDER\nBefore the Court is defendant's motion for summary judgment. For the reasons below it is granted in part. Background. Discussion. Conclusion.`,
    () => `Letters of guardianship are issued to ${who()}, who is appointed by this Court as guardian of the estate, with bond of ${usd(5000, 50000)}.`],
  OTHER: [() => `Discharge summary\nPatient ${who()} admitted ${day()} with a sprained wrist. Follow up in two weeks.`,
    () => `Employment offer. Dear ${who()}, we are glad to offer you the post of clerk at ${firm()} at ${usd(30000, 70000)} a year starting ${day()}.`,
    () => `Appraisal report\nSubject ${place()}\nComparable 1 ${usd(100000, 400000)}, comparable 2 ${usd(100000, 400000)}. Opinion of market value ${usd(100000, 400000)} as of ${day()}`,
    () => `Form 1099-INT Interest income 2025\nPayer Sample Bank  Recipient ${who()}  Box 1 ${usd(10, 900)}`,
    () => `Last will of ${who()}. I leave my books to ${who()} and the rest to my children in equal shares. Two witnesses.`,
    () => `Purchase agreement\nBuyer ${who()} offers ${usd(90000, 400000)} for ${place()}, earnest money ${usd(1000, 9000)}, closing on ${day()}, subject to inspection.`],
};
const noise = (t) => t.replace(/[a-zA-Z0-9]/g, (c) => (rnd() < 0.04 ? pick(['l', 'i', 'o', 'c', 'e', '1', '0', '']) : c));
const docs = [];
for (const type of TYPES) {
  const layouts = ({ A, B, C })[SET][type];
  const times = Math.ceil(12 / layouts.length);
  for (const layout of layouts) for (let i = 0; i < times; i++) docs.push({ type, text: i % 4 === 3 ? noise(layout()) : layout(), noisy: i % 4 === 3 });
}
const schema = { type: 'object', additionalProperties: false, properties: { type: { type: 'string', enum: TYPES }, confidence: { type: 'number', minimum: 0, maximum: 1 } }, required: ['type', 'confidence'] };
async function ask(text) {
  const r = await (await fetch(`${process.env.OLLAMA ?? 'http://localhost:11434'}/api/chat`, { method: 'POST', body: JSON.stringify({ model: process.env.MODEL ?? 'qwen3.5:9b', stream: false, think: false, keep_alive: '10m', format: schema, options: { temperature: 0, num_ctx: 8192, num_predict: 100 }, messages: [{ role: 'system', content: PROMPT }, { role: 'user', content: `<DOCUMENT>\n${text.slice(0, 4000)}\n</DOCUMENT>` }] }) })).json();
  try { const a = JSON.parse(r.message.content); return TYPES.includes(a.type) ? a : { type: 'UNREAD', confidence: 0 }; } catch { return { type: 'UNREAD', confidence: 0 }; }
}
await ask('warm up');
const started = Date.now();
const table = {}; const conf = [];
for (const d of docs) {
  const a = await ask(d.text);
  ((table[d.type] ??= {})[a.type] ??= 0); table[d.type][a.type]++;
  conf.push({ right: a.type === d.type, confidence: a.confidence, noisy: d.noisy, type: d.type });
}
const ms = (Date.now() - started) / docs.length;
const right = conf.filter((c) => c.right).length;
console.log(`set ${SET}: ${right} of ${docs.length} right (${(100 * right / docs.length).toFixed(1)}%), noisy ${conf.filter((c) => c.noisy && c.right).length} of ${conf.filter((c) => c.noisy).length}, ${Math.round(ms)} ms a document`);
for (const t of TYPES) {
  const all = Object.values(table[t]).reduce((x, y) => x + y, 0);
  console.log(`${t.padEnd(19)} ${table[t][t] ?? 0}/${all}  ${Object.entries(table[t]).filter(([k]) => k !== t).map(([k, v]) => `${k} ${v}`).join(', ')}`);
}
for (const t of [0.5, 0.8, 0.9, 0.95, 0.99]) {
  const sure = conf.filter((c) => c.confidence >= t);
  console.log(`confidence >= ${t}: ${sure.length}, wrong ${sure.filter((c) => !c.right).length}`);
}
