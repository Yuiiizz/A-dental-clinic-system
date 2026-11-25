package cn.dental.shared.web;
/** Unchecked business exception: default Spring transactions roll back. Never carry private response text. */
public final class ApiException extends RuntimeException {
    private final ApiErrorCode code;
    public ApiException(ApiErrorCode code){super(code.name());this.code=code;}
    public ApiErrorCode code(){return code;}
}
