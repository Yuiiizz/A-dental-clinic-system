package cn.dental.shared.web;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public final class RequestTraceFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE="dental.request_id";
    private static final Logger LOG=LoggerFactory.getLogger(RequestTraceFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String id=UUID.randomUUID().toString();var previous=MDC.getCopyOfContextMap();
        request.setAttribute(ATTRIBUTE,id);response.setHeader("X-Request-Id",id);MDC.put("request_id",id);
        boolean failed=false;
        try {chain.doFilter(request,response);}
        catch(ServletException|IOException|RuntimeException e){failed=true;throw e;}
        finally {
            MDC.put("http_status",Integer.toString(failed?500:response.getStatus()));
            LOG.info(failed?"REQUEST_FAILED":"REQUEST_COMPLETED");
            if(previous==null)MDC.clear();else MDC.setContextMap(previous);
        }
    }
    public static String requestId(HttpServletRequest request){
        Object id=request.getAttribute(ATTRIBUTE);
        if(id instanceof String text)return text;
        String created=UUID.randomUUID().toString();request.setAttribute(ATTRIBUTE,created);return created;
    }
}
