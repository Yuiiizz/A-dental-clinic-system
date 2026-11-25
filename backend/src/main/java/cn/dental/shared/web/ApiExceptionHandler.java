package cn.dental.shared.web;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.slf4j.*;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.LinkedHashMap;
import java.util.Map;
@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(ApiExceptionHandler.class);
    private ResponseEntity<ApiError> error(ApiErrorCode code,HttpServletRequest request){
        String id=RequestTraceFilter.requestId(request);
        return ResponseEntity.status(code.status).header("X-Request-Id",id).body(ApiError.of(code,id));
    }
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> business(ApiException e,HttpServletRequest r){return error(e.code(),r);}
    @ExceptionHandler(ApiImpactException.class)
    public ResponseEntity<Map<String,Object>> impact(ApiImpactException e,HttpServletRequest r){
        String id=RequestTraceFilter.requestId(r);
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("code",e.code().name());
        body.put("message",e.code().message);
        body.put("request_id",id);
        body.put("impact",e.impact());
        return ResponseEntity.status(e.code().status).header("X-Request-Id",id).body(body);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e,HttpServletRequest r){
        for(Throwable cause=e;cause!=null;cause=cause.getCause())
            if(cause instanceof UnrecognizedPropertyException)return error(ApiErrorCode.UNKNOWN_FIELD,r);
        return error(ApiErrorCode.INVALID_REQUEST,r);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> invalid(Exception e,HttpServletRequest r){return error(ApiErrorCode.VALIDATION_FAILED,r);}
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(Exception e,HttpServletRequest r){return error(ApiErrorCode.METHOD_NOT_ALLOWED,r);}
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> media(Exception e,HttpServletRequest r){return error(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,r);}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e,HttpServletRequest r){
        LOG.error("REQUEST_FAILED");return error(ApiErrorCode.INTERNAL_ERROR,r);
    }
}
