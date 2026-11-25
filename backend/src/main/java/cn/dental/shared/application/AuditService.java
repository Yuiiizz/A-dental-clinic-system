package cn.dental.shared.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 追加式审计入口（audit_log）。
 *
 * 摘要采用类型化字段白名单，不是任意 before/after 字符串，也不是关键词黑名单：
 * 只允许 {@link #ALLOWED_SUMMARY_FIELDS} 中显式列出的字段，值必须为字符串、有长度与条数上限。
 * 凭据（密码/令牌/绑定凭证明文）、完整请求响应、完整健康正文等字段根本不在白名单内，
 * 字段白名单限制内容结构，不识别被错误放入合法字段的秘密；调用方仍须提供最小必要摘要。
 * 新增业务字段须显式声明，不能加入患者/医生姓名等当前无需重复保存的字段。
 *
 * 事务边界：user/system 使用 MANDATORY 传播，必须在调用方已有事务内执行（与业务修改同提交
 * 或同回滚）；无事务调用会抛 IllegalTransactionStateException，绝不独立提交审计或瞬放锁。
 */
@Service
public class AuditService {
    /** 审计摘要字段白名单（正向允许，仅这些字段可写入 before/after）。 */
    private static final Set<String> ALLOWED_SUMMARY_FIELDS = Set.of(
        "enabled", "state_from", "state_to",
        "role_code", "branch_ids", "branch_id", "approval_basis",
        "schedule_starts_at", "schedule_ends_at", "visit_type", "queue_number",
        "verification_method", "verification_result", "resolution", "result_count", "match_type", "in_person",
        "code", "name", "address", "phone", "license_no", "license_valid_to",
        "opening_hours", "license_no_expiry_confirmed", "verified_by", "verified_at",
        "opening_from", "opening_to", "opening_interval_count",
        "self_checkin_enabled", "early_minutes", "late_minutes", "slot_step_minutes",
        "credential_version", "review_id", "service_item_ids");

    private static final int MAX_SUMMARY_FIELDS = 16;
    private static final int MAX_FIELD_VALUE_LENGTH = 200;
    private static final int MAX_ADDRESS_LENGTH = 300;
    private static final int MAX_ENCODED_LENGTH = 2000;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 人工操作审计（actor_type='用户'）。before/after 为白名单摘要，可为 null 表示无。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void user(long actorAccountId, String targetType, long targetId, String operation, Map<String, String> before, Map<String, String> after, String reason) {
        insert(actorAccountId, "用户", targetType, targetId, operation, encode(before), encode(after), reason);
    }

    /** 系统事件审计（actor_type='系统'，无人工操作者）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void system(String targetType, long targetId, String operation, Map<String, String> before, Map<String, String> after, String reason) {
        insert(null, "系统", targetType, targetId, operation, encode(before), encode(after), reason);
    }

    private void insert(Long actorAccountId, String actorType, String targetType, long targetId, String operation, String before, String after, String reason) {
        if (targetId <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (targetType == null || targetType.isBlank() || targetType.length() > 40) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (operation == null || operation.isBlank() || operation.length() > 60) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (reason != null && reason.length() > 300) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.update("""
            INSERT INTO audit_log(actor_account_id,actor_type,target_type,target_id,operation,before_data,after_data,reason,occurred_at)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,
            actorAccountId, actorType, targetType, targetId, operation, before, after, reason, Timestamp.from(clock.instant()));
    }

    /** 白名单校验并按字段名排序编码为稳定 JSON；null 输入编码为 null（表示无摘要）。 */
    private static String encode(Map<String, String> fields) {
        if (fields == null) return null;
        if (fields.size() > MAX_SUMMARY_FIELDS) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String key = e.getKey();
            if (key == null || !ALLOWED_SUMMARY_FIELDS.contains(key)) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED); // 白名单外字段一律拒绝
            }
            String value = e.getValue();
            int limit = "service_item_ids".equals(key) ? 1000 : "address".equals(key) ? MAX_ADDRESS_LENGTH : MAX_FIELD_VALUE_LENGTH;
            if (value == null || value.length() > limit) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            }
            sorted.put(key, value);
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(jsonString(e.getValue()));
        }
        sb.append('}');
        if (sb.length() > MAX_ENCODED_LENGTH) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return sb.toString();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
