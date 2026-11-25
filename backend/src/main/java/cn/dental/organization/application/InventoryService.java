package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ordinary consumables only. Every receiving write is posted in one transaction. */
@Service
public class InventoryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final Clock clock;

    public InventoryService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, AuditService audit, Clock clock) {
        this.jdbc=jdbc; this.authorization=authorization; this.idempotency=idempotency;
        this.audit=audit; this.clock=clock;
    }

    public record ItemCreate(String code, String name, String specification, String baseUnit,
            String storageCondition) {
        @Override public String toString() { return "ItemCreate[redacted]"; }
    }
    public record WarehouseCreate(long branchId, String name) {
        @Override public String toString() { return "WarehouseCreate[redacted]"; }
    }
    public record ReceiptLine(int lineNo, long stockItemId, long warehouseId, String manufacturer,
            String holderName, String batchNo, String expiresOn, String traceReference,
            BigDecimal quantity, BigDecimal unitCost) {
        @Override public String toString() { return "ReceiptLine[redacted]"; }
    }
    public record ReceiptCreate(long branchId, long supplierId, String documentNo,
            String supplierDocument, List<ReceiptLine> lines) {
        @Override public String toString() { return "ReceiptCreate[redacted]"; }
    }
    private record ValidLine(int no, long item, long warehouse, String maker, String holder,
            String batch, LocalDate expiry, String trace, BigDecimal quantity, BigDecimal cost) {}

    @Transactional(readOnly=true)
    public PageResult<Map<String,Object>> items(SessionService.Principal actor, String cursor, Integer requestedLimit) {
        requireAny(actor, "inventory.view");
        int limit=PageResult.validateLimit(requestedLimit);
        long after=idCursor(cursor);
        var rows=jdbc.queryForList("""
            SELECT stock_item_id,code,name,kind,specification,base_unit,storage_condition,enabled
            FROM stock_item WHERE stock_item_id>? AND kind='耗材' ORDER BY stock_item_id LIMIT ?
            """, after, limit+1);
        return page(rows,limit,"stock_item_id");
    }

    @Transactional
    public Map<String,Object> createItem(SessionService.Principal actor, ItemCreate command, String key) {
        requireAny(actor,"inventory.configure");
        if(command==null) throw invalid();
        String code=text(command.code(),40), name=text(command.name(),120);
        String specification=text(command.specification(),120), unit=text(command.baseUnit(),20);
        String storage=text(command.storageCondition(),200);
        String content=IdempotencyService.canonicalize(Map.of("code",code,"name",name,
            "specification",specification,"base_unit",unit,"storage_condition",storage));
        try {
            var outcome=idempotency.execute(actor.accountId(),"stock-item-create",key,content,
                ()->requireAny(actor,"inventory.configure"),
                ()->{
                    requireAny(actor,"inventory.configure");
                    Long id=jdbc.queryForObject("""
                        INSERT INTO stock_item(code,name,kind,specification,base_unit,storage_condition,enabled)
                        VALUES(?,?,'耗材',?,?,?,true) RETURNING stock_item_id
                        """,Long.class,code,name,specification,unit,storage);
                    audit.user(actor.accountId(),"stock_item",id,"create",null,
                        Map.of("code",code,"enabled","true"),"建立普通耗材目录");
                    return new IdempotencyService.BusinessResult(id,"created",null);
                },id->{ requireAny(actor,"inventory.configure"); itemDetail(id); });
            return itemDetail(outcome.targetId());
        } catch(DuplicateKeyException ex) { throw new ApiException(ApiErrorCode.STOCK_ITEM_CODE_EXISTS); }
    }

    @Transactional(readOnly=true)
    public PageResult<Map<String,Object>> warehouses(SessionService.Principal actor, long branch,
            String cursor, Integer requestedLimit) {
        requireBranch(actor,"inventory.view",branch,false);
        int limit=PageResult.validateLimit(requestedLimit);
        var rows=jdbc.queryForList("""
            SELECT warehouse_id,branch_id,name,enabled FROM warehouse
            WHERE branch_id=? AND warehouse_id>? ORDER BY warehouse_id LIMIT ?
            """,branch,idCursor(cursor),limit+1);
        return page(rows,limit,"warehouse_id");
    }

    @Transactional
    public Map<String,Object> createWarehouse(SessionService.Principal actor, WarehouseCreate command, String key) {
        if(command==null) throw invalid();
        long branch=positive(command.branchId());
        requireBranch(actor,"inventory.configure",branch,false);
        String name=text(command.name(),80);
        String content=IdempotencyService.canonicalize(Map.of("branch_id",branch,"name",name));
        try {
            var outcome=idempotency.execute(actor.accountId(),"warehouse-create",key,content,
                ()->requireBranch(actor,"inventory.configure",branch,false),
                ()->{
                    lock("branch","branch_id",branch);
                    requireBranch(actor,"inventory.configure",branch,false);
                    Long id=jdbc.queryForObject("""
                        INSERT INTO warehouse(branch_id,name,enabled) VALUES(?,?,true) RETURNING warehouse_id
                        """,Long.class,branch,name);
                    audit.user(actor.accountId(),"warehouse",id,"create",null,
                        Map.of("branch_ids",Long.toString(branch),"enabled","true"),"建立门店库位");
                    return new IdempotencyService.BusinessResult(id,"created",null);
                },id->warehouseDetail(actor,id,branch));
            return warehouseDetail(actor,outcome.targetId(),branch);
        } catch(DuplicateKeyException ex) { throw new ApiException(ApiErrorCode.WAREHOUSE_EXISTS); }
    }

    @Transactional(readOnly=true)
    public PageResult<Map<String,Object>> receipts(SessionService.Principal actor, long branch,
            Long batch, String cursor, Integer requestedLimit) {
        requireBranch(actor,"inventory.view",branch,false);
        int limit=PageResult.validateLimit(requestedLimit);
        String sql="""
            SELECT r.receipt_id,r.branch_id,r.supplier_id,r.document_no,r.supplier_document,
                r.received_at,r.accepted_by,r.state
            FROM purchase_receipt r WHERE r.branch_id=? AND r.state='已过账' AND r.receipt_id>?
            """;
        List<Object> args=new ArrayList<>(List.of(branch,idCursor(cursor)));
        if(batch!=null) {
            sql+=" AND EXISTS(SELECT 1 FROM purchase_receipt_line l WHERE l.receipt_id=r.receipt_id AND l.batch_id=?)";
            args.add(positive(batch));
        }
        sql+=" ORDER BY r.receipt_id LIMIT ?";
        args.add(limit+1);
        return page(jdbc.queryForList(sql,args.toArray()),limit,"receipt_id");
    }

    @Transactional(readOnly=true)
    public Map<String,Object> receipt(SessionService.Principal actor,long id) {
        Map<String,Object> row=receiptHeader(id);
        requireBranch(actor,"inventory.view",number(row,"branch_id"),true);
        return receiptDetail(id,row);
    }

    @Transactional
    public Map<String,Object> receive(SessionService.Principal actor, ReceiptCreate command, String key) {
        if(command==null) throw invalid();
        long branch=positive(command.branchId()), supplier=positive(command.supplierId());
        requireBranch(actor,"inventory.receive",branch,false);
        String document=text(command.documentNo(),80), supplierDocument=text(command.supplierDocument(),100);
        List<ValidLine> lines=validateLines(command.lines());
        List<Map<String,Object>> fingerprint=new ArrayList<>();
        for(var l:lines) {
            var m=new LinkedHashMap<String,Object>();
            m.put("line_no",l.no); m.put("stock_item_id",l.item); m.put("warehouse_id",l.warehouse);
            m.put("manufacturer",l.maker); m.put("holder_name",l.holder); m.put("batch_no",l.batch);
            m.put("expires_on",l.expiry.toString()); m.put("trace_reference",l.trace);
            m.put("quantity",l.quantity); m.put("unit_cost",l.cost); fingerprint.add(m);
        }
        String content=IdempotencyService.canonicalize(Map.of("branch_id",branch,"supplier_id",supplier,
            "document_no",document,"supplier_document",supplierDocument,"lines",fingerprint));
        try {
            var outcome=idempotency.execute(actor.accountId(),"purchase-receive",key,content,
                ()->requireBranch(actor,"inventory.receive",branch,false),
                ()->post(actor,branch,supplier,document,supplierDocument,lines),
                id->{
                    Map<String,Object> row=receiptHeader(id);
                    if(number(row,"branch_id")!=branch) throw new ApiException(ApiErrorCode.NOT_FOUND);
                    requireBranch(actor,"inventory.receive",branch,true);
                });
            Map<String,Object> row=receiptHeader(outcome.targetId());
            requireBranch(actor,"inventory.receive",branch,true);
            return receiptDetail(outcome.targetId(),row);
        } catch(DuplicateKeyException ex) {
            throw new ApiException(ApiErrorCode.RECEIPT_DOCUMENT_EXISTS);
        }
    }

    private IdempotencyService.BusinessResult post(SessionService.Principal actor,long branch,long supplier,
            String document,String supplierDocument,List<ValidLine> lines) {
        lock("branch","branch_id",branch);
        lock("supplier","supplier_id",supplier);
        for(long id:lines.stream().map(ValidLine::warehouse).distinct().sorted().toList()) lock("warehouse","warehouse_id",id);
        for(long id:lines.stream().map(ValidLine::item).distinct().sorted().toList()) lock("stock_item","stock_item_id",id);
        // Stable business-key locks serialize first batch creation across warehouses.
        List<ValidLine> byBatch=lines.stream().sorted(Comparator.comparing((ValidLine l)->l.item)
            .thenComparing(l->l.maker).thenComparing(l->l.batch)).toList();
        for(var l:byBatch) jdbc.execute("SELECT pg_advisory_xact_lock("+batchLock(l)+")");
        requireBranch(actor,"inventory.receive",branch,false);
        LocalDate today=LocalDate.ofInstant(clock.instant(),ZONE);
        var suppliers=jdbc.queryForList("""
            SELECT enabled,valid_to,verified_by,verified_at FROM supplier WHERE supplier_id=?
            """,supplier);
        if(suppliers.size()!=1) throw new ApiException(ApiErrorCode.STOCK_RECEIVE_UNAVAILABLE);
        var sr=suppliers.getFirst();
        Date valid=(Date)sr.get("valid_to");
        if(!Boolean.TRUE.equals(sr.get("enabled")) || sr.get("verified_by")==null || sr.get("verified_at")==null
                || valid==null || valid.toLocalDate().isBefore(today))
            throw new ApiException(ApiErrorCode.STOCK_RECEIVE_UNAVAILABLE);
        for(var l:lines) {
            var wh=jdbc.queryForList("SELECT branch_id,enabled FROM warehouse WHERE warehouse_id=?",l.warehouse);
            var item=jdbc.queryForList("SELECT kind,enabled FROM stock_item WHERE stock_item_id=?",l.item);
            if(wh.size()!=1 || item.size()!=1 || number(wh.getFirst(),"branch_id")!=branch
                    || !Boolean.TRUE.equals(wh.getFirst().get("enabled"))
                    || !"耗材".equals(item.getFirst().get("kind")) || !Boolean.TRUE.equals(item.getFirst().get("enabled"))
                    || l.expiry.isBefore(today)) throw new ApiException(ApiErrorCode.STOCK_RECEIVE_UNAVAILABLE);
        }
        Timestamp now=Timestamp.from(clock.instant());
        Long receipt=jdbc.queryForObject("""
            INSERT INTO purchase_receipt(branch_id,supplier_id,document_no,supplier_document,
                received_at,accepted_by,state,request_key)
            VALUES(?,?,?,?,?,?,'草稿',?) RETURNING receipt_id
            """,Long.class,branch,supplier,document,supplierDocument,now,actor.staffId(),UUID.randomUUID().toString());
        for(var l:lines) {
            long batch=findOrCreateBatch(l);
            Long line=jdbc.queryForObject("""
                INSERT INTO purchase_receipt_line(receipt_id,line_no,batch_id,warehouse_id,quantity,unit_cost)
                VALUES(?,?,?,?,?,?) RETURNING receipt_line_id
                """,Long.class,receipt,l.no,batch,l.warehouse,l.quantity,l.cost);
            jdbc.update("""
                INSERT INTO stock_movement(receipt_line_id,warehouse_id,kind,quantity_delta,
                    occurred_at,posted_at,operator_id,evidence,request_key)
                VALUES(?,?,'采购入库',?,?,?,?,?,?)
                """,line,l.warehouse,l.quantity,now,now,actor.staffId(),"采购验收 "+document,UUID.randomUUID().toString());
        }
        // A wait on an existing batch row may cross the Shanghai date boundary.
        LocalDate postingDay=LocalDate.ofInstant(clock.instant(),ZONE);
        if(valid.toLocalDate().isBefore(postingDay)
                || lines.stream().anyMatch(l->l.expiry.isBefore(postingDay)))
            throw new ApiException(ApiErrorCode.STOCK_RECEIVE_UNAVAILABLE);
        requireBranch(actor,"inventory.receive",branch,false);
        jdbc.update("UPDATE purchase_receipt SET state='已过账' WHERE receipt_id=?",receipt);
        audit.user(actor.accountId(),"purchase_receipt",receipt,"post",null,
            Map.of("branch_ids",Long.toString(branch),"state_to","已过账"),"普通耗材人工验收入库");
        return new IdempotencyService.BusinessResult(receipt,"posted",null);
    }

    private long findOrCreateBatch(ValidLine l) {
        var rows=jdbc.queryForList("""
            SELECT batch_id,holder_name,expires_on,trace_reference,state FROM stock_batch
            WHERE stock_item_id=? AND manufacturer=? AND batch_no=? FOR UPDATE
            """,l.item,l.maker,l.batch);
        if(!rows.isEmpty()) {
            var row=rows.getFirst();
            if(!Objects.equals(row.get("holder_name"),l.holder)
                    || !Objects.equals(((Date)row.get("expires_on")).toLocalDate(),l.expiry)
                    || !Objects.equals(row.get("trace_reference"),l.trace))
                throw new ApiException(ApiErrorCode.STOCK_BATCH_CONFLICT);
            if(!"合格".equals(row.get("state"))
                    || ((Date)row.get("expires_on")).toLocalDate().isBefore(LocalDate.ofInstant(clock.instant(),ZONE)))
                throw new ApiException(ApiErrorCode.STOCK_RECEIVE_UNAVAILABLE);
            return number(row,"batch_id");
        }
        return jdbc.queryForObject("""
            INSERT INTO stock_batch(stock_item_id,manufacturer,holder_name,batch_no,expires_on,trace_reference,state)
            VALUES(?,?,?,?,?,?,'合格') RETURNING batch_id
            """,Long.class,l.item,l.maker,l.holder,l.batch,Date.valueOf(l.expiry),l.trace);
    }

    @Transactional(readOnly=true)
    public PageResult<Map<String,Object>> balances(SessionService.Principal actor,long branch,
            Long warehouse,Long item,Long batch,String cursor,Integer requestedLimit) {
        requireBranch(actor,"inventory.view",branch,false);
        int limit=PageResult.validateLimit(requestedLimit);
        long[] after=pairCursor(cursor);
        String sql="""
            SELECT w.warehouse_id,w.branch_id,w.name AS warehouse_name,b.batch_id,b.batch_no,
                b.manufacturer,b.expires_on,b.state AS batch_state,i.stock_item_id,i.code,
                i.name AS item_name,i.base_unit,sum(m.quantity_delta) AS quantity
            FROM stock_movement m
            JOIN purchase_receipt_line l ON l.receipt_line_id=m.receipt_line_id
            JOIN purchase_receipt r ON r.receipt_id=l.receipt_id AND r.state='已过账'
            JOIN warehouse w ON w.warehouse_id=m.warehouse_id
            JOIN stock_batch b ON b.batch_id=l.batch_id
            JOIN stock_item i ON i.stock_item_id=b.stock_item_id
            WHERE r.branch_id=? AND (w.warehouse_id,b.batch_id)>(?,?)
            """;
        List<Object> args=new ArrayList<>(List.of(branch,after[0],after[1]));
        if(warehouse!=null) { sql+=" AND w.warehouse_id=?";args.add(positive(warehouse)); }
        if(item!=null) { sql+=" AND i.stock_item_id=?";args.add(positive(item)); }
        if(batch!=null) { sql+=" AND b.batch_id=?";args.add(positive(batch)); }
        sql+="""
            GROUP BY w.warehouse_id,w.branch_id,w.name,b.batch_id,b.batch_no,b.manufacturer,
                b.expires_on,b.state,i.stock_item_id,i.code,i.name,i.base_unit
            ORDER BY w.warehouse_id,b.batch_id LIMIT ?
            """;
        args.add(limit+1);
        var rows=jdbc.queryForList(sql,args.toArray());
        boolean more=rows.size()>limit;
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows.subList(0,Math.min(rows.size(),limit))) {
            var copy=new LinkedHashMap<String,Object>(row);
            for(String field:List.of("warehouse_id","branch_id","batch_id","stock_item_id"))
                copy.put(field,String.valueOf(row.get(field)));
            copy.put("expires_on",row.get("expires_on").toString());
            result.add(copy);
        }
        String next=more ? result.getLast().get("warehouse_id")+":"+result.getLast().get("batch_id") : null;
        return new PageResult<>(result,next);
    }

    private List<ValidLine> validateLines(List<ReceiptLine> input) {
        if(input==null || input.isEmpty() || input.size()>50) throw invalid();
        var seen=new HashSet<Integer>();
        var lines=new ArrayList<ValidLine>();
        for(var line:input) {
            if(line==null || line.lineNo()<=0 || !seen.add(line.lineNo())) throw invalid();
            lines.add(new ValidLine(line.lineNo(),positive(line.stockItemId()),positive(line.warehouseId()),
                text(line.manufacturer(),120),optional(line.holderName(),120),text(line.batchNo(),80),
                date(line.expiresOn()),optional(line.traceReference(),300),
                decimal(line.quantity(),14,3),decimal(line.unitCost(),14,4)));
        }
        lines.sort(Comparator.comparingInt(ValidLine::no));
        return List.copyOf(lines);
    }

    private Map<String,Object> itemDetail(long id) {
        var rows=jdbc.queryForList("""
            SELECT stock_item_id,code,name,kind,specification,base_unit,storage_condition,enabled
            FROM stock_item WHERE stock_item_id=? AND kind='耗材'
            """,positive(id));
        if(rows.size()!=1) throw new ApiException(ApiErrorCode.NOT_FOUND);
        var result=new LinkedHashMap<String,Object>(rows.getFirst());
        result.put("stock_item_id",Long.toString(id));
        return result;
    }
    private Map<String,Object> warehouseDetail(SessionService.Principal actor,long id,long expectedBranch) {
        var rows=jdbc.queryForList("SELECT warehouse_id,branch_id,name,enabled FROM warehouse WHERE warehouse_id=?",positive(id));
        if(rows.size()!=1 || number(rows.getFirst(),"branch_id")!=expectedBranch)
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        requireBranch(actor,"inventory.configure",expectedBranch,true);
        var result=new LinkedHashMap<String,Object>(rows.getFirst());
        result.put("warehouse_id",Long.toString(id));
        result.put("branch_id",Long.toString(expectedBranch));
        return result;
    }
    private Map<String,Object> receiptHeader(long id) {
        var rows=jdbc.queryForList("""
            SELECT receipt_id,branch_id,supplier_id,document_no,supplier_document,received_at,accepted_by,state
            FROM purchase_receipt WHERE receipt_id=? AND state='已过账'
            """,positive(id));
        if(rows.size()!=1) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    private Map<String,Object> receiptDetail(long id,Map<String,Object> header) {
        var result=new LinkedHashMap<String,Object>(header);
        for(String field:List.of("receipt_id","branch_id","supplier_id","accepted_by"))
            result.put(field,String.valueOf(header.get(field)));
        result.put("received_at",((Timestamp)header.get("received_at")).toInstant().toString());
        var lines=jdbc.queryForList("""
            SELECT l.receipt_line_id,l.line_no,l.warehouse_id,l.quantity,l.unit_cost,
                b.batch_id,b.batch_no,b.manufacturer,b.holder_name,b.expires_on,b.trace_reference,b.state AS batch_state,
                i.stock_item_id,i.code AS item_code,i.name AS item_name,i.base_unit
            FROM purchase_receipt_line l JOIN stock_batch b ON b.batch_id=l.batch_id
            JOIN stock_item i ON i.stock_item_id=b.stock_item_id
            WHERE l.receipt_id=? ORDER BY l.line_no
            """,id);
        BigDecimal total=BigDecimal.ZERO;
        var details=new ArrayList<Map<String,Object>>();
        for(var line:lines) {
            var copy=new LinkedHashMap<String,Object>(line);
            for(String field:List.of("receipt_line_id","warehouse_id","batch_id","stock_item_id"))
                copy.put(field,String.valueOf(line.get(field)));
            copy.put("expires_on",line.get("expires_on").toString());
            total=total.add(((BigDecimal)line.get("quantity")).multiply((BigDecimal)line.get("unit_cost")));
            details.add(copy);
        }
        result.put("lines",details);
        result.put("acquisition_cost_total",total.setScale(2,RoundingMode.HALF_UP));
        result.put("cost_note","库存取得成本参考；不是已付款、应付款或税额");
        return result;
    }

    private void requireAny(SessionService.Principal actor,String permission) {
        requireStaff(actor);
        Integer found=jdbc.queryForObject("""
            SELECT count(*) FROM account_role ar JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission p ON p.permission_id=rp.permission_id AND p.code=?
            JOIN role_branch_scope scope ON scope.account_id=ar.account_id AND scope.role_id=ar.role_id
            JOIN staff_branch sb ON sb.branch_id=scope.branch_id AND sb.staff_id=? AND sb.enabled AND sb.verified_at IS NOT NULL
            JOIN branch b ON b.branch_id=scope.branch_id AND b.state='营业' AND b.verified_at IS NOT NULL
            JOIN organization o ON o.organization_id=b.organization_id AND o.state='营业'
            WHERE ar.account_id=?
            """,Integer.class,permission,actor.staffId(),actor.accountId());
        if(found==null || found==0) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }
    private void requireBranch(SessionService.Principal actor,String permission,long branch,boolean hidden) {
        requireStaff(actor);
        if(branch<=0) throw new ApiException(hidden?ApiErrorCode.NOT_FOUND:ApiErrorCode.VALIDATION_FAILED);
        Integer found=jdbc.queryForObject("""
            SELECT count(*) FROM account_role ar JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission p ON p.permission_id=rp.permission_id AND p.code=?
            JOIN role_branch_scope scope ON scope.account_id=ar.account_id AND scope.role_id=ar.role_id AND scope.branch_id=?
            JOIN staff_branch sb ON sb.branch_id=scope.branch_id AND sb.staff_id=? AND sb.enabled AND sb.verified_at IS NOT NULL
            JOIN branch b ON b.branch_id=scope.branch_id AND b.state='营业' AND b.verified_at IS NOT NULL
            JOIN organization o ON o.organization_id=b.organization_id AND o.state='营业'
            WHERE ar.account_id=?
            """,Integer.class,permission,branch,actor.staffId(),actor.accountId());
        if(found==null || found==0) throw new ApiException(hidden?ApiErrorCode.NOT_FOUND:ApiErrorCode.FORBIDDEN);
    }
    private void requireStaff(SessionService.Principal actor) {
        if(actor==null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        authorization.requireCurrent(actor);
        if(!SessionService.STAFF.equals(actor.clientType()) || actor.mustChangePassword() || actor.staffId()==null)
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        Integer organizations=jdbc.queryForObject("SELECT count(*) FROM organization",Integer.class);
        if(organizations==null || organizations!=1) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }
    private void lock(String table,String pk,long id) {
        // Identifiers are fixed local literals, never request input.
        if(jdbc.queryForList("SELECT "+pk+" FROM "+table+" WHERE "+pk+"=? FOR UPDATE",id).size()!=1)
            throw new ApiException(ApiErrorCode.STOCK_RECEIVE_UNAVAILABLE);
    }
    private static long batchLock(ValidLine line) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(
                ("stock-batch\0"+line.item+"\0"+line.maker+"\0"+line.batch).getBytes(StandardCharsets.UTF_8));
            long value=0;
            for(int n=0;n<8;n++) value=(value<<8)|(digest[n]&0xffL);
            return value;
        } catch(Exception ex) { throw new IllegalStateException("SHA-256 unavailable",ex); }
    }
    private static PageResult<Map<String,Object>> page(List<Map<String,Object>> rows,int limit,String idField) {
        boolean more=rows.size()>limit;
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows.subList(0,Math.min(rows.size(),limit))) {
            var copy=new LinkedHashMap<String,Object>(row);
            copy.put(idField,String.valueOf(row.get(idField)));
            for(var field:List.of("branch_id","supplier_id","accepted_by"))
                if(copy.containsKey(field)) copy.put(field,String.valueOf(row.get(field)));
            if(copy.get("received_at") instanceof Timestamp t) copy.put("received_at",t.toInstant().toString());
            result.add(copy);
        }
        return new PageResult<>(result,more?String.valueOf(result.getLast().get(idField)):null);
    }
    private static String text(String value,int max) {
        if(value==null || value.isBlank()) throw invalid();
        String clean=value.trim();
        if(clean.length()>max || clean.indexOf('\0')>=0) throw invalid();
        return clean;
    }
    private static String optional(String value,int max) {
        if(value==null || value.isBlank()) return null;
        return text(value,max);
    }
    private static LocalDate date(String value) {
        try { return LocalDate.parse(value); }
        catch(DateTimeParseException | NullPointerException ex) { throw invalid(); }
    }
    private static BigDecimal decimal(BigDecimal value,int precision,int scale) {
        if(value==null || value.signum()<=0 || value.scale()>scale
                || value.precision()-value.scale()>precision-scale) throw invalid();
        return value;
    }
    private static long positive(long value) { if(value<=0) throw invalid(); return value; }
    private static long idCursor(String cursor) {
        if(cursor==null) return 0;
        try { return positive(Long.parseLong(cursor)); }
        catch(NumberFormatException ex) { throw invalid(); }
    }
    private static long[] pairCursor(String cursor) {
        if(cursor==null) return new long[]{0,0};
        String[] pair=cursor.split(":",-1);
        if(pair.length!=2) throw invalid();
        try { return new long[]{positive(Long.parseLong(pair[0])),positive(Long.parseLong(pair[1]))}; }
        catch(NumberFormatException ex) { throw invalid(); }
    }
    private static long number(Map<String,Object> row,String field) { return ((Number)row.get(field)).longValue(); }
    private static ApiException invalid() { return new ApiException(ApiErrorCode.VALIDATION_FAILED); }
}
