package cn.dental.identity.config;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.ApiErrorCode;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.core.userdetails.*;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.json.JsonMapper;
@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http,SessionService sessions,JsonMapper json)throws Exception{
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            // Replaced by session-specific constant-time CSRF verification above, NOT removed for staff writes.
            .csrf(c->c.disable())
            .addFilterBefore(new SessionAuthenticationFilter(sessions,json),AnonymousAuthenticationFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->SessionAuthenticationFilter.error(q,r,json,ApiErrorCode.UNAUTHENTICATED))
                .accessDeniedHandler((q,r,x)->SessionAuthenticationFilter.error(q,r,json,ApiErrorCode.FORBIDDEN)))
            .authorizeHttpRequests(a->a
                .requestMatchers(HttpMethod.GET,"/api/v1/public/**").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/wechat","/api/v1/auth/patient-register","/api/v1/auth/patient-bind","/api/v1/auth/staff-login").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/auth/me").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/logout").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/password-change").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/accounts").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/accounts").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/accounts/*/role-scopes").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.PUT,"/api/v1/staff/accounts/*/role-scopes").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/accounts/*/disable").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/staff-members").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/staff-members").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/staff-members/*").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/staff-members/*/credentials").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/staff-members/*/credentials/*/review").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/staff-members/*/credentials/*/revoke").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/staff-members/*/credentials/*/reviews").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/staff-members/*/credentials/*/facts").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/scheduling").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/scheduling/schedules").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/scheduling/schedules/*/publish").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/scheduling/service-items").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/scheduling/resources").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.PUT,"/api/v1/staff/scheduling/staff/*/branches/*").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.PUT,"/api/v1/staff/scheduling/staff/*/services").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/treatment-plans","/api/v1/staff/treatment-plans/list").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/treatment-plans","/api/v1/staff/treatment-plans/*/submit","/api/v1/staff/treatment-plans/*/items/*/status").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/me/treatment-plans").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.POST,"/api/v1/me/treatment-plans/*/accept","/api/v1/me/treatment-plans/*/reject").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/billing").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/bills","/api/v1/staff/bills/*/payments","/api/v1/staff/refunds","/api/v1/staff/refunds/*/process","/api/v1/staff/invoices","/api/v1/staff/invoices/*/issue","/api/v1/staff/invoices/*/void","/api/v1/staff/invoices/*/red","/api/v1/staff/treatment-plans/*/bill").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/me/billing").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/clinical","/api/v1/staff/visits","/api/v1/staff/followup-plans").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/appointments/*/start-visit","/api/v1/staff/visits","/api/v1/staff/followup-plans").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/me/records").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.GET,"/api/v1/me/profile").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.POST,"/api/v1/me/profile").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.GET,"/api/v1/me/doctor-preferences").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.PUT,"/api/v1/me/doctor-preferences/*").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.DELETE,"/api/v1/me/doctor-preferences/*").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.GET,"/api/v1/me/booking","/api/v1/me/appointments").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.GET,"/api/v1/me/changes").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.POST,"/api/v1/me/changes/*/response").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.POST,"/api/v1/me/appointments","/api/v1/me/appointments/*/cancel","/api/v1/me/appointments/*/reschedule","/api/v1/me/appointments/*/checkin").hasAuthority("CLIENT_patient_miniapp")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/booking").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/internal-messages","/api/v1/staff/internal-messages/contacts").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/internal-messages").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/suppliers","/api/v1/staff/suppliers/*").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/suppliers").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/stock-items","/api/v1/staff/warehouses",
                    "/api/v1/staff/purchase-receipts","/api/v1/staff/purchase-receipts/*",
                    "/api/v1/staff/stock-balances").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/stock-items","/api/v1/staff/warehouses",
                    "/api/v1/staff/purchase-receipts").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/appointments").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/changes").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/appointments/*/changes",
                    "/api/v1/staff/changes/*/response","/api/v1/staff/changes/*/apply",
                    "/api/v1/staff/changes/*/withdraw")
                    .hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/contacts","/api/v1/staff/contacts/due").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/contacts").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/dentist-unavailability",
                    "/api/v1/staff/resource-unavailability","/api/v1/staff/resources")
                    .hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/dentist-unavailability",
                    "/api/v1/staff/dentist-unavailability/*/resolve",
                    "/api/v1/staff/resource-unavailability",
                    "/api/v1/staff/resource-unavailability/*/resolve").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/queue").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/appointments").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/appointments/*/cancel").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/appointments/*/checkin").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/branches").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/branches").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/branches/*").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.PATCH,"/api/v1/staff/branches/*").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/branches/*/submit","/api/v1/staff/branches/*/verify",
                    "/api/v1/staff/branches/*/disable","/api/v1/staff/branches/*/reopen").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/branches/*/opening-intervals").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.PUT,"/api/v1/staff/branches/*/opening-intervals").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/branches/*/booking-config").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.PUT,"/api/v1/staff/branches/*/booking-config").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/branches/*/checkin-code").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/patients").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/patients/*/summary").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.GET,"/api/v1/staff/patients/*/reception-summary").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/patients").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/patients/*/selection").hasAuthority("CLIENT_staff_web")
                .requestMatchers(HttpMethod.POST,"/api/v1/staff/patients/*/binding-token","/api/v1/staff/patients/*/binding-token/reissue").hasAuthority("CLIENT_staff_web")
                // Business routes stay closed until their role + branch policies are implemented.
                .anyRequest().denyAll());
        return http.build();
    }
    @Bean UserDetailsService noDefaultPasswordLogin(){return name->{throw new UsernameNotFoundException("Use the clinic session login");};}
}
