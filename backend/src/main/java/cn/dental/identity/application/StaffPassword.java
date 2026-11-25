package cn.dental.identity.application;
import cn.dental.shared.web.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
@Component
public class StaffPassword {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummy = encoder.encode("synthetic-dummy-not-a-user-password");
    public String normalize(String name) {
        if(name==null) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String value=name.strip().toLowerCase(Locale.ROOT);
        if(!value.matches("[a-z0-9][a-z0-9._-]{2,99}")) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return value;
    }
    public void validate(String password) {
        if(password==null || password.codePointCount(0,password.length())<12 || password.getBytes(StandardCharsets.UTF_8).length>72 || password.isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }
    public String encode(String password) {validate(password);return encoder.encode(password);}
    public boolean matches(String password,String hash) {
        if(password==null || password.getBytes(StandardCharsets.UTF_8).length>72) return false;
        boolean usable=hash!=null && hash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}");
        boolean matched=encoder.matches(password,usable?hash:dummy);
        return usable && matched;
    }
}
