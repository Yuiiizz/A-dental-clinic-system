package cn.dental.shared.web;
import com.fasterxml.jackson.annotation.JsonValue;
/** Explicit ID type; never convert monetary/quantity Long values globally. */
public record ApiId(long value) {
    public ApiId { if(value<=0) throw new IllegalArgumentException("ID must be positive"); }
    @JsonValue public String decimal(){return Long.toString(value);}
}
