package cn.dental.organization.application;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class CredentialValidityTest {
    private final LocalDate date=LocalDate.of(2026,10,26);
    @Test void midnightIsExclusiveButOneNanosecondLaterNeedsNextDay() {
        Instant start=Instant.parse("2026-10-26T15:00:00Z"), end=Instant.parse("2026-10-26T16:00:00Z");
        assertTrue(CredentialValidity.coversInterval("verified",date,date,false,start,end));
        assertFalse(CredentialValidity.coversInterval("verified",date,date,false,start,end.plusNanos(1)));
    }
    @Test void futureIntervalRequiresWholePeriod() {
        Instant start=Instant.parse("2026-10-28T02:00:00Z"),end=start.plusSeconds(3600);
        assertFalse(CredentialValidity.coversInterval("verified",date,date,false,start,end));
        assertTrue(CredentialValidity.coversInterval("verified",date.plusDays(2),date.plusDays(2),false,start,end));
    }
    @Test void invalidFactsAndNonVerifiedStatesNeverQualify() {
        for(String state:new String[]{"pending","rejected"}) assertFalse(CredentialValidity.onDate(state,null,null,true,date));
        assertFalse(CredentialValidity.onDate("verified",null,null,false,date));
        assertFalse(CredentialValidity.onDate("verified",null,date,true,date));
        assertFalse(CredentialValidity.onDate("verified",date.plusDays(1),date,false,date));
        assertTrue(CredentialValidity.onDate("verified",null,null,true,date));
    }
    @Test void invalidIntervalsRejected() {
        Instant now=Instant.parse("2026-10-26T02:00:00Z");
        assertThrows(IllegalArgumentException.class,()->CredentialValidity.coversInterval("verified",null,null,true,now,now));
        assertThrows(IllegalArgumentException.class,()->CredentialValidity.coversInterval("verified",null,null,true,now,now.minusSeconds(1)));
    }
}
