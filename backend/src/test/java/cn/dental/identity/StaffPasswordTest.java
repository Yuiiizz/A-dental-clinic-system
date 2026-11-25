package cn.dental.identity;
import cn.dental.identity.application.*;
import cn.dental.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class StaffPasswordTest {
 @Test void hashesAndBoundsPasswordsWithoutSilentTruncation(){
  var p=new StaffPassword();String hash=p.encode("Synthetic-password-2026");
  assertTrue(p.matches("Synthetic-password-2026",hash));assertFalse(p.matches("wrong",hash));
  assertFalse(p.matches("Synthetic-password-2026","invalid"));
  assertThrows(ApiException.class,()->p.encode("短密码"));assertThrows(ApiException.class,()->p.encode("长".repeat(25)));
  assertEquals("admin.name",p.normalize(" Admin.Name "));
 }
 @Test void limiterRejectsRepeatedAttempts(){
  var limiter=new LoginThrottle(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
  for(int i=0;i<10;i++)limiter.check("name","127.0.0.1");
  assertThrows(ApiException.class,()->limiter.check("name","127.0.0.2"));
 }
}
