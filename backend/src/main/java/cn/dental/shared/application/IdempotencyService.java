package cn.dental.shared.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一幂等执行（operation_request）。
 *
 * 作用域：actor_account_id + operation + request_key（与表 UNIQUE 约束一致）。
 * 流程：先重新验证身份与权限 → 计算规范化请求摘要 → advisory 事务锁串行化同键并发
 * → 取得锁后再次验证权限 → 有记录则同摘要重放（并校验目标仍可见）/ 不同摘要 409
 * → 无记录则落占位、执行业务、回写结果，同一事务提交。
 *
 * 并发边界（授权与提交之间，不是“再鉴权一次就消除所有竞争”）：
 * 授权是“时间点检查”。取得锁后的二次鉴权能覆盖“等待同键锁期间被停用/撤权”的场景；
 * 但若撤权在二次鉴权之后、业务提交之前才提交，本方法无法观察到（本方法不与账号管理
 * 的事务级锁 734192014 共享同一把锁，否则会把预约与账号维护串行化）。因此保证的是：
 * 在取得锁（或二次鉴权读取）之前已提交的撤权必然被拒绝；与业务提交真正并发的撤权仍属
 * 授权状态变更与业务写入之间的既有竞争窗口，需由调用方在更上层以账号锁协同。
 *
 * 事务与失败边界：隔离级别按 PostgreSQL 默认 READ COMMITTED；幂等记录、业务修改与审计
 * 在同一事务提交，业务失败整体回滚（占位记录一并回滚），客户端以同键重试即可恢复；
 * 已提交但响应丢失时，同键重试命中 completed 记录返回原结果，不会重复执行。
 * 不自动重试任何数据库错误、唯一冲突、资源冲突或权限错误；这些一律原样抛出，绝不吞异常。
 */
@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 业务执行结果：仅引用与状态，不含完整响应。 */
    public record BusinessResult(long targetId, String resultCode, String resultRef) {
        public BusinessResult {
            if (targetId <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            if (resultCode == null || resultCode.isBlank() || resultCode.length() > 40) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            if (resultRef != null && resultRef.length() > 40) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
    }

    /** 对调用方的执行结果：replayed 标记是否命中重放。 */
    public record Outcome(long targetId, String resultCode, String resultRef, boolean replayed) {}

    /**
     * 执行幂等保护下的业务动作。
     *
     * @param actorAccountId   发起账号（幂等作用域之一）
     * @param operation        操作标识（≤80，幂等作用域之一）
     * @param requestKey       客户端 Idempotency-Key（≤64，幂等作用域之一）
     * @param requestContent   经过校验、规范化的业务输入（决定 request_hash）
     * @param requireAuthorized 重新验证当前身份与操作权限；进锁前与取得锁后各执行一次，不得为 null
     * @param business          业务动作，返回业务引用；失败抛异常则整体回滚，不得为 null
     * @param requireVisible    重放时校验目标记录仍可见；不得为 null
     */
    @Transactional
    public Outcome execute(
            long actorAccountId,
            String operation,
            String requestKey,
            String requestContent,
            Runnable requireAuthorized,
            Supplier<BusinessResult> business,
            LongConsumer requireVisible) {
        Objects.requireNonNull(requireAuthorized, "requireAuthorized 不能为 null（不得以 null 跳过鉴权）");
        Objects.requireNonNull(business, "business 不能为 null");
        Objects.requireNonNull(requireVisible, "requireVisible 不能为 null（不得以 null 跳过可见性校验）");
        validate(actorAccountId, operation, requestKey, requestContent);

        // Verify the effective connection, including an outer transaction joined by REQUIRED.
        // Declaring isolation on this method alone cannot override an existing transaction.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            if (connection.getAutoCommit() || connection.isReadOnly()
                    || connection.getTransactionIsolation() != java.sql.Connection.TRANSACTION_READ_COMMITTED) {
                throw new org.springframework.transaction.IllegalTransactionStateException(
                    "Idempotency requires a writable READ COMMITTED transaction");
            }
            return null;
        });

        // 进锁前先鉴权（“先通过”）。
        requireAuthorized.run();

        String hash = sha256Hex(requestContent);
        long lockKey = lockKey(actorAccountId, operation, requestKey);
        // 事务级 advisory 锁：同键并发串行化，第二个请求等首个提交后再读，天然只生效一次。
        jdbc.execute("SELECT pg_advisory_xact_lock(" + lockKey + ")");

        // 取得锁后重新鉴权：等待期间可能被停用/撤权，不得借旧结果绕过。
        requireAuthorized.run();

        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT request_hash,target_id,result_code,result_ref,completed_at FROM operation_request WHERE actor_account_id=? AND operation=? AND request_key=?",
            actorAccountId, operation, requestKey);

        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.getFirst();
            if (!hash.equals(row.get("request_hash"))) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            // advisory 锁下不会出现“处理中”；此处仅兜底，绝不把处理中/响应未知伪装为成功。
            if (row.get("completed_at") == null) throw new ApiException(ApiErrorCode.INTERNAL_ERROR);
            long targetId = ((Number) row.get("target_id")).longValue();
            requireVisible.accept(targetId);
            return new Outcome(targetId, (String) row.get("result_code"), (String) row.get("result_ref"), true);
        }

        // 新请求：先落占位记录（与业务同事务），再执行业务，最后回写结果。
        jdbc.update(
            "INSERT INTO operation_request(operation_id,actor_account_id,operation,request_key,request_hash,created_at) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID(), actorAccountId, operation, requestKey, hash, Timestamp.from(clock.instant()));

        BusinessResult result = business.get();
        jdbc.update(
            "UPDATE operation_request SET target_id=?,result_code=?,result_ref=?,completed_at=? WHERE actor_account_id=? AND operation=? AND request_key=?",
            result.targetId(), result.resultCode(), result.resultRef(), Timestamp.from(clock.instant()),
            actorAccountId, operation, requestKey);
        return new Outcome(result.targetId(), result.resultCode(), result.resultRef(), false);
    }

    private void validate(long actorAccountId, String operation, String requestKey, String requestContent) {
        if (actorAccountId <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (operation == null || operation.isBlank() || operation.length() > 80) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 64) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (requestContent == null || requestContent.isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }

    /**
     * 规范化摘要：按键排序的稳定序列化，不依赖字段顺序、不丢弃业务字段。
     * 键必须为字符串；值仅支持 null/String/整数(Byte..Long,BigInteger)/BigDecimal/Boolean/Map/List；
     * 拒绝 Float/Double（含 NaN/Infinity）及其他未知类型，避免浮点小数表示歧义与静默丢字段。
     */
    public static String canonicalize(Map<String, ?> values) {
        if (values == null) throw new IllegalArgumentException("canonicalize: values 不能为 null");
        StringBuilder sb = new StringBuilder(256);
        appendMap(sb, values);
        return sb.toString();
    }

    private static void appendMap(StringBuilder sb, Map<?, ?> map) {
        // 键必须是字符串；非字符串键直接拒绝（不 String.valueOf 静默转换，避免数字 1 与字符串 "1" 覆盖）。
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                    "canonicalize: 键必须为字符串，得到 " + (e.getKey() == null ? "null" : e.getKey().getClass().getName()));
            }
            sorted.put(key, e.getValue());
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendString(sb, e.getKey()).append(':');
            appendValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            appendString(sb, s);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            sb.append(((Number) value).longValue());
        } else if (value instanceof BigInteger bi) {
            sb.append(bi.toString());
        } else if (value instanceof BigDecimal bd) {
            sb.append(canonicalDecimal(bd));
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Map<?, ?> m) {
            appendMap(sb, m);
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                appendValue(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Number n) {
            // Float/Double 等未明确支持的类型（含 NaN/Infinity）一律拒绝。
            throw new IllegalArgumentException("canonicalize: 不支持的数值类型 " + n.getClass().getName() + "（请使用整数或 BigDecimal）");
        } else {
            throw new IllegalArgumentException("canonicalize: 不支持的类型 " + value.getClass().getName());
        }
    }

    /** BigDecimal 规范化：去尾随零、禁用科学计数；零统一为 "0"。 */
    private static String canonicalDecimal(BigDecimal bd) {
        if (bd.compareTo(BigDecimal.ZERO) == 0) return "0";
        return bd.stripTrailingZeros().toPlainString();
    }

    private static StringBuilder appendString(StringBuilder sb, String s) {
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
        return sb;
    }

    private static long lockKey(long actorAccountId, String operation, String requestKey) {
        byte[] h = sha256Bytes(actorAccountId + "\u0000" + operation + "\u0000" + requestKey);
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xFFL);
        return v;
    }

    private static String sha256Hex(String input) {
        return HexFormat.of().formatHex(sha256Bytes(input));
    }

    private static byte[] sha256Bytes(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
