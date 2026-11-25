package cn.dental.shared.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预约业务行锁封装。固定锁顺序规避相反顺序竞争：
 * 患者 → 涉及医生按 staff_id 升序 → 排班 → 资源按 resource_id 升序 → 子记录 → 队列计数器最后。
 *
 * 每个方法仅锁定对应业务行，不新增一把全局锁串行化全部预约；
 * 也不改动账号管理既有事务级锁（734192013/734192014）。
 *
 * 事务边界：所有方法使用 MANDATORY，必须在调用方已有事务内执行；无事务调用抛
 * IllegalTransactionStateException，避免 FOR UPDATE 在自动提交下被立即释放而形同虚设。
 * 空 ID 集合是明确的 no-op（无对应业务对象，无需加锁）；null/非正整数 ID 一律拒绝，不静默丢弃。
 *
 * 并发声明：固定顺序只在本模块按序调用时避免锁序相反导致的死锁，不保证全系统无死锁或
 * 无序列化失败；数据库错误由上层事务整体回滚并决定重试策略。
 */
@Repository
public class BusinessLockRepository {
    private final JdbcTemplate jdbc;

    public BusinessLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 第 1 步：锁定患者。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPatient(long patientId) {
        requirePositive("patientId", patientId);
        jdbc.queryForObject("SELECT patient_id FROM patient WHERE patient_id=? FOR UPDATE", Long.class, patientId);
    }

    /** 第 2 步：按 staff_id 升序锁定涉及医生（牙医是 staff 表 staff_type='牙医' 的行）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockDentists(Collection<Long> dentistIds) {
        for (long id : sorted(dentistIds)) {
            jdbc.queryForObject("SELECT staff_id FROM staff WHERE staff_id=? FOR UPDATE", Long.class, id);
        }
    }

    /** 第 3 步：锁定排班。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSchedule(long scheduleId) {
        requirePositive("scheduleId", scheduleId);
        jdbc.queryForObject("SELECT schedule_id FROM schedule WHERE schedule_id=? FOR UPDATE", Long.class, scheduleId);
    }

    /** 第 4 步：按 resource_id 升序锁定资源。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockResources(Collection<Long> resourceIds) {
        for (long id : sorted(resourceIds)) {
            jdbc.queryForObject("SELECT resource_id FROM resource WHERE resource_id=? FOR UPDATE", Long.class, id);
        }
    }

    /** 第 5 步：锁定预约子记录（预约拟做项目）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockChildRecords(long appointmentId) {
        requirePositive("appointmentId", appointmentId);
        jdbc.queryForList("SELECT appointment_id,item_id FROM appointment_item WHERE appointment_id=? ORDER BY item_id FOR UPDATE", appointmentId);
    }

    /** 第 6 步（最后）：锁定候诊号计数器（要求该行已存在；签到流程负责按需创建）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockQueueCounter(long branchId, LocalDate queueDate) {
        requirePositive("branchId", branchId);
        if (queueDate == null) throw new IllegalArgumentException("queueDate 不能为 null");
        jdbc.queryForObject("SELECT branch_id FROM queue_counter WHERE branch_id=? AND queue_date=? FOR UPDATE", Long.class, branchId, java.sql.Date.valueOf(queueDate));
    }

    private static void requirePositive(String name, long id) {
        if (id <= 0) throw new IllegalArgumentException(name + " 必须为正整数，得到 " + id);
    }

    /** 去重、升序排序；null/非正整数拒绝，空集合返回空列表（no-op，不加锁）。 */
    private static List<Long> sorted(Collection<Long> ids) {
        if (ids == null) throw new IllegalArgumentException("锁 ID 集合不能为 null");
        for (Long id : ids) {
            if (id == null || id <= 0) throw new IllegalArgumentException("锁 ID 必须为正整数，得到 " + id);
        }
        return ids.stream().distinct().sorted().toList();
    }
}
