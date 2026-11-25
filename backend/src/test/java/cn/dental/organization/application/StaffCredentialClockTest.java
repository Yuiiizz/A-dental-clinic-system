package cn.dental.organization.application;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StaffCredentialClockTest {
    @Test void effectiveUsesInjectedBusinessDayAndCredentialState() {
        LocalDate today = LocalDate.of(2026, 10, 26);
        assertTrue(StaffService.credentialEffective("verified", null, today, false, today));
        assertTrue(StaffService.credentialEffective("verified", today, today, false, today));
        assertFalse(StaffService.credentialEffective("verified", null, today.minusDays(1), false, today));
        assertFalse(StaffService.credentialEffective("verified", today.plusDays(1), null, false, today));
        assertFalse(StaffService.credentialEffective("pending", null, null, true, today));
        assertFalse(StaffService.credentialEffective("rejected", null, null, true, today));
        assertFalse(StaffService.credentialEffective("verified", null, null, false, today));
        assertTrue(StaffService.credentialEffective("verified", null, null, true, today));
        assertTrue(StaffService.credentialEffective("verified", today.minusDays(10), null, true, today));
        assertFalse(StaffService.credentialEffective("verified", today.plusDays(1), null, true, today));
    }

    @Test void commandStringsDoNotExposeFields() {
        var credential = new StaffService.CredentialCommand("dentist_practice", "CERT-SECRET", "2026-01-01", "2030-01-01", false);
        var command = new StaffService.CreateCommand("姓名不应出现", "牙医", "牙医", "PRACTICE-SECRET", credential);
        assertFalse(credential.toString().contains("CERT-SECRET"));
        assertFalse(command.toString().contains("姓名不应出现"));
        assertFalse(command.toString().contains("PRACTICE-SECRET"));
    }
}
