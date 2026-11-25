package cn.dental;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A口腔诊所系统 后端入口。
 *
 * <p>当前为 Phase 1（Setup）最小可构建骨架：仅声明应用入口，未包含任何业务逻辑、
 * 数据库迁移或身份授权实现。业务模块（identity/organization/scheduling/booking/
 * coordination/checkin/policy/shared）在后续任务中按计划新增。</p>
 */
@SpringBootApplication
public class AdenDentalApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdenDentalApplication.class, args);
    }
}
