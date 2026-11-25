package cn.dental.shared.web;
import java.util.List;
public record PageResult<T>(List<T> items,String next_cursor) {
    public PageResult { items=List.copyOf(items); }
    public static int validateLimit(Integer limit){
        if(limit==null)return 20;
        if(limit<1||limit>100)throw new ApiException(ApiErrorCode.INVALID_REQUEST);
        return limit;
    }
}
