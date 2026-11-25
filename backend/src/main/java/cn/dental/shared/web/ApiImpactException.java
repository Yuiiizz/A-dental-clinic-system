package cn.dental.shared.web;

/** 带类型化影响摘要的业务冲突；不携带患者身份或病历文本。 */
public final class ApiImpactException extends RuntimeException {
    private final ApiErrorCode code;
    private final ApiImpact impact;

    public ApiImpactException(ApiErrorCode code, ApiImpact impact) {
        super(code.name());
        this.code = code;
        this.impact = impact;
    }

    public ApiErrorCode code() {
        return code;
    }

    public ApiImpact impact() {
        return impact;
    }

    public record ApiImpact(
            long schedule_count,
            long appointment_count,
            String first_starts_at,
            String last_ends_at) {}
}
