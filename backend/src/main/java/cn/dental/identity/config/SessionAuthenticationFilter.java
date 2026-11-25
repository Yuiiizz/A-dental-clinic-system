package cn.dental.identity.config;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

public final class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final SessionService sessions;private final JsonMapper json;
    public SessionAuthenticationFilter(SessionService sessions,JsonMapper json){this.sessions=sessions;this.json=json;}
    public static void error(HttpServletRequest request,HttpServletResponse response,JsonMapper json,ApiErrorCode code)throws IOException{
        response.setStatus(code.status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        String id=RequestTraceFilter.requestId(request);response.setHeader("X-Request-Id",id);
        response.getWriter().write(json.writeValueAsString(ApiError.of(code,id)));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        try {
            var authorizations=Collections.list(request.getHeaders("Authorization"));
            var cookies=new ArrayList<String>();if(request.getCookies()!=null)for(var cookie:request.getCookies())if(cookie.getName().equals("A_CLINIC_SESSION"))cookies.add(cookie.getValue());
            if(authorizations.size()>1||cookies.size()>1||(!authorizations.isEmpty()&&!cookies.isEmpty()))throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
            // Re-login must work with an expired browser cookie. Never accept patient Bearer on staff login.
            String loginPath=request.getRequestURI().substring(request.getContextPath().length());
            boolean login="POST".equals(request.getMethod()) && "/api/v1/auth/staff-login".equals(loginPath);
            if(login && !authorizations.isEmpty())throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
            if(login)cookies.clear();
            String token=null,type=null;
            if(!authorizations.isEmpty()){
                String header=authorizations.getFirst();if(!header.startsWith("Bearer "))throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
                token=header.substring(7);type=SessionService.PATIENT;
            } else if(!cookies.isEmpty()){token=cookies.getFirst();type=SessionService.STAFF;}
            if(token!=null){
                var principal=sessions.authenticate(token,type);
                String path=request.getRequestURI().substring(request.getContextPath().length());
                if(principal.mustChangePassword()&&SessionService.STAFF.equals(type)&&!Set.of("/api/v1/auth/me","/api/v1/auth/password-change","/api/v1/auth/logout").contains(path))throw new ApiException(ApiErrorCode.FORBIDDEN);
                if(SessionService.STAFF.equals(type)&&!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
                    var headers=Collections.list(request.getHeaders("X-CSRF-Token"));
                    if(headers.size()!=1)throw new ApiException(ApiErrorCode.FORBIDDEN);
                    sessions.requireCsrf(principal,headers.getFirst());
                }
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of(new SimpleGrantedAuthority("CLIENT_"+type))));
                SecurityContextHolder.setContext(context);
            }
        } catch(ApiException e){error(request,response,json,e.code());return;}
        try {chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}
    }
}
