package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.finding.EntityType;

import java.util.EnumSet;
import java.util.Set;

import static com.securedoc.platform.domain.finding.EntityType.BANK_ACCOUNT_NUMBER;
import static com.securedoc.platform.domain.finding.EntityType.BANK_ROUTING_NUMBER;
import static com.securedoc.platform.domain.finding.EntityType.CREDIT_CARD_NUMBER;
import static com.securedoc.platform.domain.finding.EntityType.CREDIT_SCORE;
import static com.securedoc.platform.domain.finding.EntityType.CVV;
import static com.securedoc.platform.domain.finding.EntityType.DATE_OF_BIRTH;
import static com.securedoc.platform.domain.finding.EntityType.DRIVERS_LICENSE_NUMBER;
import static com.securedoc.platform.domain.finding.EntityType.EIN;
import static com.securedoc.platform.domain.finding.EntityType.ITIN;
import static com.securedoc.platform.domain.finding.EntityType.PASSPORT_NUMBER;
import static com.securedoc.platform.domain.finding.EntityType.PIN;
import static com.securedoc.platform.domain.finding.EntityType.SSN;

/**
 * Task 0062 (R1): a regulation pack is a named set of catalog kinds an entity switches on together. What it covers and
 * what it doesn't is said in plain words: a pack helps with a rule, it is not compliance with it. The names are stored.
 */
public enum RegulationPack {
    GLBA("GLBA / financial",
            "Customer financial information: Social Security Numbers, ITINs, dates of birth, bank account and routing "
                    + "numbers, card numbers, card security codes, PINs, credit scores, driver’s license and "
                    + "passport numbers.",
            "Names, addresses, income, balances and loan terms are not found.",
            SSN, ITIN, DATE_OF_BIRTH, BANK_ACCOUNT_NUMBER, BANK_ROUTING_NUMBER, CREDIT_CARD_NUMBER, CREDIT_SCORE,
            DRIVERS_LICENSE_NUMBER, PASSPORT_NUMBER, CVV, PIN),
    PCI_DSS("PCI DSS",
            "Payment card data: full card numbers, card security codes (CVV) and PINs. All three are locked items, "
                    + "always removed in full.",
            "Cardholder names, expiration dates and magnetic stripe data are not found.",
            CREDIT_CARD_NUMBER, CVV, PIN),
    HIPAA("HIPAA",
            "The HIPAA identifiers that LexaVault finds: Social Security Numbers, ITINs, dates of birth, bank account "
                    + "and card numbers, driver’s license and passport numbers.",
            "Names, addresses, phone numbers, emails, medical record and health plan numbers, other dates, photos and "
                    + "the remaining HIPAA identifiers are not found.",
            SSN, ITIN, DATE_OF_BIRTH, BANK_ACCOUNT_NUMBER, CREDIT_CARD_NUMBER, DRIVERS_LICENSE_NUMBER,
            PASSPORT_NUMBER),
    FRCP_5_2("FRCP 5.2 (court filings)",
            "Federal court filings: Social Security and taxpayer numbers (SSN, ITIN, EIN), dates of birth, bank "
                    + "account and card numbers. The rule allows the last four digits of these numbers; here never of a "
                    + "card number, which is a locked item.",
            "Names of minors are not found. A date of birth is covered in full (the rule allows the year).",
            SSN, ITIN, EIN, DATE_OF_BIRTH, BANK_ACCOUNT_NUMBER, CREDIT_CARD_NUMBER);

    private final String displayName;
    private final String covers;
    private final String notCovered;
    private final Set<EntityType> kinds;

    RegulationPack(String displayName, String covers, String notCovered, EntityType first, EntityType... more) {
        this.displayName = displayName;
        this.covers = covers;
        this.notCovered = notCovered;
        this.kinds = Set.copyOf(EnumSet.of(first, more));
    }

    public String displayName() {
        return displayName;
    }

    public String covers() {
        return covers;
    }

    public String notCovered() {
        return notCovered;
    }

    public Set<EntityType> kinds() {
        return kinds;
    }
}
