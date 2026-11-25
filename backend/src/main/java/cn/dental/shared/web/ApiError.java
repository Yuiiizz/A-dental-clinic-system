package cn.dental.shared.web;
public record ApiError(String code,String message,String request_id) {
    public static ApiError of(ApiErrorCode code,String requestId){return new ApiError(code.name(),code.message,requestId);}
}
