package com.securedoc.platform.api.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0017 A3: the reports CSV export neutralises a value cell that would start a spreadsheet formula. */
class ReportsCsvFormulaCellTest {

    @Test
    void valueCellsAreNeutralisedAndPlainValuesAreUnchanged() {
        assertEquals("Pages,12\r\n", OperationsController.row("Pages", 12, ""));
        assertEquals("Review rate,40%\r\n", OperationsController.row("Review rate", 40, "%"));
        assertEquals("Source filter,'=cmd|' /C calc'!A0\r\n",
                OperationsController.row("Source filter", "=cmd|' /C calc'!A0", ""));
        assertEquals("Outcome filter,'@SUM(1)\r\n", OperationsController.row("Outcome filter", "@SUM(1)", ""));
        assertEquals("Delivery SLA breaches,'-1\r\n", OperationsController.row("Delivery SLA breaches", -1, ""));
    }
}
