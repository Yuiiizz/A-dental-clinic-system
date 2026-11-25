package cn.dental.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 身份模块共享的事务级 advisory 锁键派生。必须与 T017-A 注册逻辑字节级一致：所有并发路径（注册、
 * 绑定、签发/重签）共用同一派生规则，命名空间各不相同以避免与账号维护锁、幂等锁冲突。
 */
public final class IdentityLocks {
    private IdentityLocks() {}

    /** 命名空间 + 若干键段 → 64 位 advisory 锁键（确定、与段顺序无关的拼接语义，命名空间避免与账号/幂等锁冲突）。 */
    public static long lockKey(String namespace, String... parts) {
        StringBuilder sb = new StringBuilder(namespace);
        for (String p : parts) sb.append('\u0000').append(p);
        byte[] h = sha256Bytes(sb.toString());
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xFFL);
        return v;
    }

    private static byte[] sha256Bytes(String input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
