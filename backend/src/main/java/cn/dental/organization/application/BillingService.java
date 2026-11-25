package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Offline billing records: bills, partial payments, refunds and manual invoices (not a payment gateway). */
@Service
public class BillingService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final cn.dental.shared.application.IdempotencyService idempotency;

    public BillingService(JdbcTemplate jdbc, AuthorizationService authorization, Clock clock, cn.dental.shared.application.IdempotencyService idempotency) {
        this.jdbc = jdbc; this.authorization = authorization; this.clock = clock; this.idempotency = idempotency;
    }
    public record ItemInput(String name, int quantity, long unitPriceCents) {}
    public record BillCommand(long visitId, List<ItemInput> items) {}
    public record PayCommand(long amountCents, String method, String note) {}
    public record RefundCommand(long billId, long paymentId, long amountCents, String reason) {}
    public record RefundDecision(String decision, String basis) {}
    public record InvoiceCommand(long billId, String title, String invoiceType, String buyerType, String buyerTaxNo, String taxTreatment, int taxRateBps, long netCents, long taxCents) {}

    @Transactional(readOnly = true)
    public Map<String, Object> overview(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        List<Long> branches = branches(actor);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);
        for (long branch : branches) authorization.requireBranch(actor, "billing.manage", branch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bills", bills(branches));
        out.put("visits", jdbc.query("""
            SELECT v.visit_id,p.name AS patient_name,a.branch_id,v.completed_at
            FROM visit_record v JOIN appointment a ON a.appointment_id=v.appointment_id
            JOIN patient p ON p.patient_id=a.patient_id
            WHERE a.branch_id IN (%s) ORDER BY v.completed_at DESC
            """.formatted(placeholders(branches)), (r, n) -> Map.of(
                "visit_id", Long.toString(r.getLong("visit_id")), "patient_name", r.getString("patient_name"),
                "branch_id", Long.toString(r.getLong("branch_id")),
                "completed_at", r.getTimestamp("completed_at").toInstant().toString()), branches.toArray()));
        out.put("payments", jdbc.query("""
            SELECT p.payment_id,p.bill_id,p.amount_cents,p.method,p.state,p.received_at
            FROM payment p JOIN bill b ON b.bill_id=p.bill_id WHERE b.branch_id IN (%s)
            ORDER BY p.payment_id DESC
            """.formatted(placeholders(branches)), (r, n) -> Map.of(
                "payment_id", Long.toString(r.getLong("payment_id")), "bill_id", Long.toString(r.getLong("bill_id")),
                "amount_cents", r.getLong("amount_cents"), "method", r.getString("method"),
                "state", r.getString("state"), "received_at", r.getTimestamp("received_at").toInstant().toString()),
            branches.toArray()));
        out.put("refunds", refunds("b.branch_id IN (%s)".formatted(placeholders(branches)), branches.toArray()));
        out.put("invoices", jdbc.query("""
            SELECT i.invoice_id,i.bill_id,i.title,i.amount_cents,i.status,i.requested_at,
                   i.invoice_type,i.invoice_no,i.buyer_type,i.buyer_tax_no,i.tax_treatment,i.tax_rate_bps,i.net_amount_cents,i.tax_amount_cents,i.red_of_id,i.red_basis
            FROM invoice i JOIN bill b ON b.bill_id=i.bill_id WHERE b.branch_id IN (%s) ORDER BY i.invoice_id DESC
            """.formatted(placeholders(branches)), (r, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("invoice_id", Long.toString(r.getLong("invoice_id")));
                m.put("bill_id", Long.toString(r.getLong("bill_id")));
                m.put("title", r.getString("title"));
                m.put("amount_cents", r.getLong("amount_cents"));
                m.put("status", r.getString("status"));
                m.put("requested_at", r.getTimestamp("requested_at").toInstant().toString());
                m.put("invoice_type", r.getString("invoice_type"));
                m.put("invoice_no", r.getString("invoice_no"));
                m.put("buyer_type", r.getString("buyer_type"));
                m.put("buyer_tax_no", r.getString("buyer_tax_no"));
                m.put("tax_treatment", r.getString("tax_treatment"));
                m.put("tax_rate_bps", r.getInt("tax_rate_bps"));
                m.put("net_amount_cents", r.getLong("net_amount_cents"));
                m.put("tax_amount_cents", r.getLong("tax_amount_cents"));
                m.put("red_of_id", r.getObject("red_of_id") == null ? null : Long.toString(r.getLong("red_of_id")));
                m.put("red_basis", r.getString("red_basis"));
                return m;
            },
            branches.toArray()));
        return out;
    }

    @Transactional
    public Map<String, Object> createBill(SessionService.Principal actor, BillCommand c) {
        if (c == null || c.visitId() <= 0 || c.items() == null || c.items().isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> visit = jdbc.queryForMap("""
            SELECT a.patient_id,a.branch_id FROM visit_record v JOIN appointment a ON a.appointment_id=v.appointment_id
            WHERE v.visit_id=?
            """, c.visitId());
        long branchId = ((Number) visit.get("branch_id")).longValue();
        requireBranch(actor, branchId);
        long total = 0;
        for (ItemInput item : c.items()) {
            if (item == null || item.name() == null || item.name().isBlank() || item.name().trim().length() > 100
                || item.quantity() <= 0 || item.unitPriceCents() < 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            total += item.quantity() * item.unitPriceCents();
        }
        if (total <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long id = jdbc.queryForObject("""
            INSERT INTO bill(patient_id,branch_id,visit_id,total_cents,paid_cents,status,version,created_at)
            VALUES(?,?,?,?,0,'待收款',1,?) RETURNING bill_id
            """, Long.class, ((Number) visit.get("patient_id")).longValue(), branchId, c.visitId(), total,
            Timestamp.from(clock.instant()));
        for (ItemInput item : c.items()) {
            long amount = (long) item.quantity() * item.unitPriceCents();
            jdbc.update("INSERT INTO bill_item(bill_id,name,quantity,unit_price_cents,amount_cents) VALUES(?,?,?,?,?)",
                id, item.name().trim(), item.quantity(), item.unitPriceCents(), amount);
        }
        return byId(id);
    }

    @Transactional
    public Map<String, Object> payOnce(SessionService.Principal actor, long billId, PayCommand c, String key) {
        if (c == null) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Runnable authorized = () -> {
            var rows = jdbc.queryForList("SELECT branch_id FROM bill WHERE bill_id=?", Long.class, billId);
            if (rows.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            requireBranch(actor, rows.getFirst());
        };
        String content = cn.dental.shared.application.IdempotencyService.canonicalize(Map.of(
            "bill_id", billId, "amount_cents", c.amountCents(), "method", c.method() == null ? "" : c.method(),
            "note", c.note() == null ? "" : c.note().strip()));
        idempotency.execute(actor.accountId(), "bill-payment", key, content, authorized, () -> {
            pay(actor, billId, c);
            return new cn.dental.shared.application.IdempotencyService.BusinessResult(billId, "RECORDED", null);
        }, target -> {
            if (target != billId) throw new ApiException(ApiErrorCode.IDEMPOTENCY_CONFLICT);
            authorized.run();
        });
        return byId(billId);
    }

    @Transactional
    public Map<String, Object> pay(SessionService.Principal actor, long billId, PayCommand c) {
        if (c == null || c.amountCents() <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (!List.of("现金", "银行卡", "微信线下登记", "其他线下").contains(c.method())) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> bill = jdbc.queryForMap("SELECT branch_id,total_cents,paid_cents,status FROM bill WHERE bill_id=? FOR UPDATE", billId);
        requireBranch(actor, ((Number) bill.get("branch_id")).longValue());
        if (!List.of("待收款", "部分收款").contains(String.valueOf(bill.get("status")))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        long total = ((Number) bill.get("total_cents")).longValue();
        long paid = ((Number) bill.get("paid_cents")).longValue();
        long remaining = total - paid;
        if (c.amountCents() > remaining) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.update("INSERT INTO payment(bill_id,amount_cents,method,state,received_by,received_at,note) VALUES(?,?,?,?,?,?,?)",
            billId, c.amountCents(), c.method(), "已登记", actor.staffId(), Timestamp.from(clock.instant()),
            c.note() == null ? null : c.note().strip());
        long nextPaid = paid + c.amountCents();
        jdbc.update("UPDATE bill SET paid_cents=?,status=?,version=version+1 WHERE bill_id=?",
            nextPaid, nextPaid >= total ? "已收款" : "部分收款", billId);
        return byId(billId);
    }

    @Transactional
    public Map<String, Object> requestRefund(SessionService.Principal actor, RefundCommand c) {
        if (c == null || c.amountCents() <= 0 || c.reason() == null || c.reason().isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> bill = jdbc.queryForMap("SELECT branch_id,paid_cents,status FROM bill WHERE bill_id=? FOR UPDATE", c.billId());
        requireBranch(actor, ((Number) bill.get("branch_id")).longValue());
        // The bill row serializes all refund reservations, including unspecified-payment requests.
        long reserved = jdbc.queryForObject("SELECT coalesce(sum(amount_cents),0) FROM refund WHERE bill_id=? AND status IN ('待处理','已退款')", Long.class, c.billId());
        if (c.amountCents() > ((Number) bill.get("paid_cents")).longValue() - reserved)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (c.paymentId() < 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (c.paymentId() > 0) {
            List<Long> amounts = jdbc.queryForList("SELECT amount_cents FROM payment WHERE payment_id=? AND bill_id=? AND state='已登记'", Long.class, c.paymentId(), c.billId());
            if (amounts.size() != 1) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            long paymentReserved = jdbc.queryForObject("SELECT coalesce(sum(amount_cents),0) FROM refund WHERE payment_id=? AND status IN ('待处理','已退款')", Long.class, c.paymentId());
            if (c.amountCents() > amounts.getFirst() - paymentReserved)
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        long id = jdbc.queryForObject("""
            INSERT INTO refund(bill_id,payment_id,amount_cents,reason,status,requested_by,requested_at)
            VALUES(?,?,?,?,'待处理',?,?) RETURNING refund_id
            """, Long.class, c.billId(), c.paymentId() > 0 ? c.paymentId() : null, c.amountCents(),
            c.reason().strip(), actor.staffId(), Timestamp.from(clock.instant()));
        return Map.of("refund_id", Long.toString(id), "status", "待处理");
    }

    @Transactional
    public Map<String, Object> processRefund(SessionService.Principal actor, long refundId, RefundDecision decision) {
        if (refundId <= 0 || decision == null || !List.of("已退款", "已拒绝").contains(decision.decision())
                || decision.basis() == null || decision.basis().isBlank() || decision.basis().strip().length() > 300)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        // The bill is the shared serialization point for every refund reservation and payment.
        List<Map<String, Object>> locations = jdbc.queryForList("""
            SELECT b.bill_id,b.branch_id FROM refund r JOIN bill b ON b.bill_id=r.bill_id WHERE r.refund_id=?
            """, refundId);
        if (locations.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long billId = ((Number) locations.getFirst().get("bill_id")).longValue();
        long branchId = ((Number) locations.getFirst().get("branch_id")).longValue();
        authorization.requireBranch(actor, "billing.refund_process", branchId);
        Map<String, Object> bill = jdbc.queryForMap("SELECT branch_id,paid_cents FROM bill WHERE bill_id=? FOR UPDATE", billId);
        authorization.requireBranch(actor, "billing.refund_process", ((Number) bill.get("branch_id")).longValue());
        Map<String, Object> refund = jdbc.queryForMap("SELECT bill_id,status FROM refund WHERE refund_id=? FOR UPDATE", refundId);
        if (((Number) refund.get("bill_id")).longValue() != billId || !"待处理".equals(refund.get("status")))
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        jdbc.update("""
            UPDATE refund SET status=?,processing_basis=?,processed_at=?,processed_by=? WHERE refund_id=?
            """, decision.decision(), decision.basis().strip(), Timestamp.from(clock.instant()), actor.staffId(), refundId);
        if ("已退款".equals(decision.decision())) {
            long refunded = jdbc.queryForObject("SELECT coalesce(sum(amount_cents),0) FROM refund WHERE bill_id=? AND status='已退款'", Long.class, billId);
            long paid = ((Number) bill.get("paid_cents")).longValue();
            if (refunded > paid) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
            jdbc.update("UPDATE bill SET status=?,version=version+1 WHERE bill_id=?",
                refunded == paid ? "已退款" : "部分退款", billId);
        }
        return Map.of("refund_id", Long.toString(refundId), "status", decision.decision());
    }

    @Transactional
    public Map<String, Object> createInvoice(SessionService.Principal actor, InvoiceCommand c) {
        if (c == null || c.billId() <= 0 || c.title() == null || c.title().isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> bill = jdbc.queryForMap("SELECT branch_id,paid_cents,status FROM bill WHERE bill_id=?", c.billId());
        requireBranch(actor, ((Number) bill.get("branch_id")).longValue());
        if (!"已收款".equals(bill.get("status"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        String type = c.invoiceType() == null || c.invoiceType().isBlank() ? "人工收据" : c.invoiceType().strip();
        if (!List.of("人工收据", "增值税普通发票", "增值税专用发票").contains(type))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (c.taxRateBps() < 0 || c.taxRateBps() > 10000) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long amount = ((Number) bill.get("paid_cents")).longValue();
        long net = c.netCents();
        long tax = c.taxCents();
        String buyerType = c.buyerType() == null || c.buyerType().isBlank() ? "个人" : c.buyerType().strip();
        String treatment = c.taxTreatment() == null || c.taxTreatment().isBlank() ? "不征税" : c.taxTreatment().strip();
        if (!List.of("个人", "单位").contains(buyerType)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (!List.of("应税", "免税", "零税率", "不征税").contains(treatment)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if ("人工收据".equals(type)) {
            if (c.buyerTaxNo() != null && c.buyerTaxNo().strip().length() > 30) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            net = amount; tax = 0; buyerType = "个人"; treatment = "不征税";
        } else {
            if ("单位".equals(buyerType) && (c.buyerTaxNo() == null || c.buyerTaxNo().isBlank() || c.buyerTaxNo().strip().length() > 30))
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            if ("增值税专用发票".equals(type) && !"单位".equals(buyerType)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            if ("应税".equals(treatment)) {
                if (c.taxRateBps() == 0 || net <= 0 || tax < 0 || net + tax != amount)
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            } else {
                if (net != amount || tax != 0 || c.taxRateBps() != 0)
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            }
        }
        long id = jdbc.queryForObject("""
            INSERT INTO invoice(bill_id,title,amount_cents,status,requested_by,requested_at,
                invoice_type,buyer_type,buyer_tax_no,tax_treatment,tax_rate_bps,net_amount_cents,tax_amount_cents)
            VALUES(?,?,?,'待开票',?,?,?,?,?,?,?,?,?) RETURNING invoice_id
            """, Long.class, c.billId(), c.title().strip(), amount, actor.staffId(), Timestamp.from(clock.instant()),
            type, buyerType, c.buyerTaxNo() == null ? null : c.buyerTaxNo().strip(), treatment,
            c.taxRateBps(), net, tax);
        return Map.of("invoice_id", Long.toString(id), "status", "待开票");
    }

    @Transactional
    public Map<String, Object> createBillFromPlan(SessionService.Principal actor, long planId) {
        Map<String, Object> plan = jdbc.queryForMap("""
            SELECT plan_id,patient_id,branch_id,state FROM treatment_plan WHERE plan_id=? FOR UPDATE
            """, planId);
        requireBranch(actor, ((Number) plan.get("branch_id")).longValue());
        if (!List.of("已接受", "已完成").contains(plan.get("state")))
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        if (jdbc.queryForObject("SELECT count(*) FROM bill WHERE plan_id=?", Integer.class, planId) != 0)
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT name,quantity,unit_price_cents FROM treatment_plan_item
            WHERE plan_id=? AND status <> '已取消' ORDER BY sort_order,item_id
            """, planId);
        if (items.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long total = 0;
        for (Map<String, Object> item : items) total += ((Number) item.get("quantity")).longValue() * ((Number) item.get("unit_price_cents")).longValue();
        if (total <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long id;
        try {
            id = jdbc.queryForObject("""
                INSERT INTO bill(patient_id,branch_id,plan_id,total_cents,paid_cents,status,version,created_at)
                VALUES(?,?,?,?,0,'待收款',1,?) RETURNING bill_id
                """, Long.class, ((Number) plan.get("patient_id")).longValue(), ((Number) plan.get("branch_id")).longValue(),
                planId, total, Timestamp.from(clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        }
        for (Map<String, Object> item : items) {
            long qty = ((Number) item.get("quantity")).longValue();
            long unit = ((Number) item.get("unit_price_cents")).longValue();
            jdbc.update("INSERT INTO bill_item(bill_id,name,quantity,unit_price_cents,amount_cents) VALUES(?,?,?,?,?)",
                id, item.get("name"), qty, unit, qty * unit);
        }
        return byId(id);
    }

    @Transactional
    public Map<String, Object> redInvoice(SessionService.Principal actor, long id) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT i.invoice_id,i.bill_id,i.title,i.amount_cents,i.invoice_type,i.buyer_type,i.buyer_tax_no,
                   i.tax_treatment,i.tax_rate_bps,i.net_amount_cents,i.tax_amount_cents,i.status,i.red_of_id,b.branch_id
            FROM invoice i JOIN bill b ON b.bill_id=i.bill_id WHERE i.invoice_id=? FOR UPDATE
            """, id);
        requireBranch(actor, ((Number) row.get("branch_id")).longValue());
        if (!"已开票".equals(row.get("status")) || row.get("red_of_id") != null)
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        String type = String.valueOf(row.get("invoice_type"));
        if ("人工收据".equals(type)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        try {
            long redId = jdbc.queryForObject("""
                INSERT INTO invoice(bill_id,title,amount_cents,status,requested_by,requested_at,
                    invoice_type,buyer_type,buyer_tax_no,tax_treatment,tax_rate_bps,net_amount_cents,tax_amount_cents,red_of_id,note)
                VALUES(?,?,?,'待开票',?,?,?,?,?,?,?,?,?,?,?)
                RETURNING invoice_id
                """, Long.class, ((Number) row.get("bill_id")).longValue(), "红冲-" + row.get("title"),
                -((Number) row.get("amount_cents")).longValue(), actor.staffId(), Timestamp.from(clock.instant()),
                type, row.get("buyer_type"), row.get("buyer_tax_no"), row.get("tax_treatment"), row.get("tax_rate_bps"),
                -((Number) row.get("net_amount_cents")).longValue(),
                -((Number) row.get("tax_amount_cents")).longValue(), id, "红冲申请，原票 " + id);
            return Map.of("invoice_id", Long.toString(redId), "red_of_id", Long.toString(id), "status", "待开票");
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        }
    }

    @Transactional
    public Map<String, Object> issueInvoice(SessionService.Principal actor, long id, String invoiceNo, String basis) {
        if (invoiceNo == null || invoiceNo.isBlank() || invoiceNo.strip().length() > 40)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT b.branch_id,i.status,i.red_of_id FROM invoice i JOIN bill b ON b.bill_id=i.bill_id WHERE i.invoice_id=? FOR UPDATE
            """, id);
        requireBranch(actor, ((Number) row.get("branch_id")).longValue());
        if (!"待开票".equals(row.get("status"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        Long redOf = row.get("red_of_id") == null ? null : ((Number) row.get("red_of_id")).longValue();
        if (redOf != null && (basis == null || basis.isBlank() || basis.strip().length() > 300))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.update("""
            UPDATE invoice SET status='已开票',invoice_no=?,issued_at=?,issued_by=?,red_basis=? WHERE invoice_id=?
            """, invoiceNo.strip(), Timestamp.from(clock.instant()), actor.staffId(),
            redOf == null ? null : basis.strip(), id);
        if (redOf != null) {
            // Only a registered, evidence-backed red completion changes the original invoice.
            int updated = jdbc.update("UPDATE invoice SET status='已红冲' WHERE invoice_id=? AND status='已开票'", redOf);
            if (updated != 1) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        }
        return Map.of("invoice_id", Long.toString(id), "status", "已开票", "invoice_no", invoiceNo.strip());
    }

    @Transactional
    public Map<String, Object> voidInvoice(SessionService.Principal actor, long id, String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 300)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT b.branch_id,i.status,i.voided_at FROM invoice i JOIN bill b ON b.bill_id=i.bill_id WHERE i.invoice_id=? FOR UPDATE
            """, id);
        requireBranch(actor, ((Number) row.get("branch_id")).longValue());
        if ("已作废".equals(row.get("status")) || "已红冲".equals(row.get("status")))
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        if (jdbc.queryForObject("""
            SELECT count(*) FROM invoice WHERE red_of_id=? AND status IN ('待开票','已开票')
            """, Integer.class, id) != 0)
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        jdbc.update("""
            UPDATE invoice SET status='已作废',voided_at=?,voided_by=?,void_reason=? WHERE invoice_id=?
            """, Timestamp.from(clock.instant()), actor.staffId(), reason.strip(), id);
        return Map.of("invoice_id", Long.toString(id), "status", "已作废");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> patientBilling(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if (actor.patientId() == null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bills", jdbc.query("""
            SELECT bill_id,patient_id,branch_id,visit_id,plan_id,total_cents,paid_cents,status,version,created_at
            FROM bill WHERE patient_id=? ORDER BY bill_id DESC
            """, (r, n) -> billRow(r), actor.patientId()));
        out.put("payments", jdbc.query("""
            SELECT p.payment_id,p.bill_id,p.amount_cents,p.method,p.state,p.received_at
            FROM payment p JOIN bill b ON b.bill_id=p.bill_id WHERE b.patient_id=? ORDER BY p.payment_id DESC
            """, (r, n) -> Map.of("payment_id", Long.toString(r.getLong("payment_id")),
                "bill_id", Long.toString(r.getLong("bill_id")), "amount_cents", r.getLong("amount_cents"),
                "method", r.getString("method"), "state", r.getString("state"),
                "received_at", r.getTimestamp("received_at").toInstant().toString()), actor.patientId()));
        out.put("refunds", refunds("b.patient_id=?", actor.patientId()));
        out.put("invoices", jdbc.query("""
            SELECT i.invoice_id,i.bill_id,i.title,i.amount_cents,i.status,i.issued_at
            FROM invoice i JOIN bill b ON b.bill_id=i.bill_id WHERE b.patient_id=? ORDER BY i.invoice_id DESC
            """, (r, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("invoice_id", Long.toString(r.getLong("invoice_id")));
                row.put("bill_id", Long.toString(r.getLong("bill_id")));
                row.put("title", r.getString("title"));
                row.put("amount_cents", r.getLong("amount_cents"));
                row.put("status", r.getString("status"));
                row.put("issued_at", r.getTimestamp("issued_at") == null ? null : r.getTimestamp("issued_at").toInstant().toString());
                return row;
            },
            actor.patientId()));
        return out;
    }

    private List<Map<String, Object>> bills(List<Long> branches) {
        return jdbc.query("""
            SELECT b.bill_id,b.patient_id,p.name AS patient_name,b.branch_id,b.visit_id,b.plan_id,b.total_cents,b.paid_cents,
                   b.status,b.version,b.created_at
            FROM bill b JOIN patient p ON p.patient_id=b.patient_id WHERE b.branch_id IN (%s)
            ORDER BY b.bill_id DESC
            """.formatted(placeholders(branches)), (r, n) -> {
            Map<String, Object> m = billRow(r);
            m.put("patient_name", r.getString("patient_name"));
            return m;
        }, branches.toArray());
    }
    private List<Map<String, Object>> refunds(String predicate, Object... args) {
        return jdbc.query("""
            SELECT r.refund_id,r.bill_id,r.payment_id,r.amount_cents,r.reason,r.status,
                   r.requested_at,r.processed_at,r.processing_basis
            FROM refund r JOIN bill b ON b.bill_id=r.bill_id WHERE %s ORDER BY r.refund_id DESC
            """.formatted(predicate), (r, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("refund_id", Long.toString(r.getLong("refund_id")));
                row.put("bill_id", Long.toString(r.getLong("bill_id")));
                row.put("payment_id", r.getObject("payment_id") == null ? null : Long.toString(r.getLong("payment_id")));
                row.put("amount_cents", r.getLong("amount_cents"));
                row.put("reason", r.getString("reason"));
                row.put("status", r.getString("status"));
                row.put("requested_at", r.getTimestamp("requested_at").toInstant().toString());
                row.put("processed_at", r.getTimestamp("processed_at") == null ? null : r.getTimestamp("processed_at").toInstant().toString());
                row.put("processing_basis", r.getString("processing_basis"));
                return row;
            }, args);
    }
    private Map<String, Object> byId(long id) {
        return jdbc.queryForObject("""
            SELECT b.bill_id,b.patient_id,p.name AS patient_name,b.branch_id,b.visit_id,b.plan_id,b.total_cents,b.paid_cents,
                   b.status,b.version,b.created_at FROM bill b JOIN patient p ON p.patient_id=b.patient_id
            WHERE b.bill_id=?
            """, (r, n) -> { Map<String, Object> m = billRow(r); m.put("patient_name", r.getString("patient_name")); return m; }, id);
    }
    private Map<String, Object> billRow(java.sql.ResultSet r) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bill_id", Long.toString(r.getLong("bill_id")));
        m.put("patient_id", Long.toString(r.getLong("patient_id")));
        m.put("branch_id", Long.toString(r.getLong("branch_id")));
        m.put("visit_id", r.getObject("visit_id") == null ? null : Long.toString(r.getLong("visit_id")));
        m.put("plan_id", r.getObject("plan_id") == null ? null : Long.toString(r.getLong("plan_id")));
        m.put("total_cents", r.getLong("total_cents"));
        m.put("paid_cents", r.getLong("paid_cents"));
        m.put("remaining_cents", Math.max(0, r.getLong("total_cents") - r.getLong("paid_cents")));
        m.put("status", r.getString("status"));
        m.put("version", r.getInt("version"));
        m.put("created_at", r.getTimestamp("created_at").toInstant().toString());
        return m;
    }
    private List<Long> branches(SessionService.Principal actor) {
        return jdbc.queryForList("""
            SELECT DISTINCT bs.branch_id FROM role_branch_scope bs
            JOIN account_role ar ON ar.account_id=bs.account_id AND ar.role_id=bs.role_id
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission pm ON pm.permission_id=rp.permission_id AND pm.code='billing.manage'
            JOIN staff_branch sb ON sb.staff_id=? AND sb.branch_id=bs.branch_id
                AND sb.enabled AND sb.verified_at IS NOT NULL
            WHERE bs.account_id=? ORDER BY bs.branch_id
            """, Long.class, actor.staffId(), actor.accountId());
    }
    private void requireBranch(SessionService.Principal actor, long branch) {
        authorization.requireBranch(actor, "billing.manage", branch);
    }
    private static String placeholders(List<?> list) { return String.join(",", Collections.nCopies(list.size(), "?")); }
}
