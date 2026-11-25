-- One-time expansion of fictional ordinary consumables in the dedicated demo database.
-- Keep the existing posted receipts and movements intact. Back up the database first.
BEGIN;

CREATE TEMP TABLE _sample_consumables (
  code text PRIMARY KEY,
  name text NOT NULL,
  specification text NOT NULL,
  base_unit text NOT NULL,
  storage_condition text NOT NULL,
  manufacturer text NOT NULL,
  batch_no text NOT NULL,
  supplier_id bigint NOT NULL,
  cloud_qty numeric(14,3) NOT NULL,
  riverside_qty numeric(14,3) NOT NULL,
  unit_cost numeric(14,4) NOT NULL
) ON COMMIT DROP;

INSERT INTO _sample_consumables VALUES
('DEMO-CONS-05','一次性丁腈检查手套','100 只/盒','盒','阴凉干燥','【示例】防护用品企业甲','DEMO-INV-B05',901,24,18,28.0000),
('DEMO-CONS-06','医用外科口罩','50 片/盒','盒','常温干燥','【示例】防护用品企业甲','DEMO-INV-B06',901,20,16,18.0000),
('DEMO-CONS-07','一次性患者围兜','50 条/包','包','常温干燥','【示例】器材生产企业乙','DEMO-INV-B07',901,15,12,32.0000),
('DEMO-CONS-08','一次性吸唾管','100 支/包','包','常温干燥','【示例】器材生产企业乙','DEMO-INV-B08',901,18,14,26.0000),
('DEMO-CONS-09','一次性强吸头','50 支/包','包','常温干燥','【示例】器材生产企业乙','DEMO-INV-B09',901,12,10,39.0000),
('DEMO-CONS-10','无菌纱布块','10 片/包','包','常温干燥','【示例】卫生材料企业丙','DEMO-INV-B10',902,30,20,9.5000),
('DEMO-CONS-11','医用棉球','100 只/袋','袋','阴凉干燥','【示例】卫生材料企业丙','DEMO-INV-B11',902,25,16,11.0000),
('DEMO-CONS-12','一次性探针','20 支/包','包','常温干燥','【示例】器材生产企业丁','DEMO-INV-B12',902,10,8,45.0000),
('DEMO-CONS-13','一次性镊子','20 支/包','包','常温干燥','【示例】器材生产企业丁','DEMO-INV-B13',902,10,8,42.0000),
('DEMO-CONS-14','牙科咬合纸','100 张/盒','盒','避光干燥','【示例】牙科材料企业戊','DEMO-INV-B14',902,14,10,36.0000),
('DEMO-CONS-15','器械灭菌袋','200 只/盒','盒','常温干燥','【示例】消毒用品企业己','DEMO-INV-B15',903,12,9,58.0000),
('DEMO-CONS-16','一次性隔离膜','1 卷/包','卷','常温干燥','【示例】消毒用品企业己','DEMO-INV-B16',903,15,12,25.0000),
('DEMO-CONS-17','一次性涂药棒','100 支/盒','盒','阴凉干燥','【示例】牙科材料企业庚','DEMO-INV-B17',903,16,12,22.0000),
('DEMO-CONS-18','牙线棒','50 支/盒','盒','常温干燥','【示例】口腔用品企业辛','DEMO-INV-B18',903,12,10,19.0000);

DO $$
BEGIN
  IF current_database() <> 'a_dental_teacher_demo'
     AND current_database() !~ '^a_dental_teacher_demo_test_[a-f0-9]{8}$' THEN
    RAISE EXCEPTION 'The target is not the dedicated fictional clinic database';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM organization WHERE organization_id=901 AND credit_code='DEMO-ORG-901')
     OR (SELECT count(*) FROM branch WHERE (branch_id=901 AND code='DEMO-01') OR (branch_id=902 AND code='DEMO-02'))<>2
     OR (SELECT count(*) FROM supplier WHERE (supplier_id,credit_code) IN ((901,'DEMO-SUP-901'),(902,'DEMO-SUP-902'),(903,'DEMO-SUP-903')))<>3
     OR NOT EXISTS (SELECT 1 FROM staff WHERE staff_id=903 AND staff_type='其他人员')
     OR (SELECT count(*) FROM warehouse WHERE (warehouse_id=901 AND branch_id=901) OR (warehouse_id=903 AND branch_id=902))<>2
     OR (SELECT count(*) FROM stock_item WHERE code IN ('DEMO-CONS-01','DEMO-CONS-02','DEMO-CONS-03','DEMO-CONS-04'))<>4 THEN
    RAISE EXCEPTION 'The target is not the expected dedicated fictional clinic database';
  END IF;
  IF EXISTS (SELECT 1 FROM stock_item WHERE code IN (SELECT code FROM _sample_consumables))
     OR EXISTS (SELECT 1 FROM purchase_receipt WHERE document_no LIKE 'DEMO-INV-20261123-%') THEN
    RAISE EXCEPTION 'Inventory sample expansion already exists or partially conflicts';
  END IF;
END $$;

INSERT INTO stock_item(code,name,kind,specification,base_unit,storage_condition)
SELECT code,name,'耗材',specification,base_unit,storage_condition FROM _sample_consumables;

INSERT INTO stock_batch(stock_item_id,manufacturer,batch_no,expires_on,state,trace_reference)
SELECT i.stock_item_id,s.manufacturer,s.batch_no,CURRENT_DATE + INTERVAL '2 years','合格',
       'DEMO-TRACE-' || s.batch_no
FROM _sample_consumables s JOIN stock_item i ON i.code=s.code;

INSERT INTO purchase_receipt(branch_id,supplier_id,document_no,supplier_document,received_at,accepted_by,state,request_key)
SELECT b.branch_id,s.supplier_id,
       'DEMO-INV-20261123-' || b.branch_id || '-' || s.supplier_id,
       '示例送货单 ' || b.branch_id || '-' || s.supplier_id,
       clock_timestamp(),903,'草稿',gen_random_uuid()::text
FROM (VALUES (901::bigint),(902::bigint)) b(branch_id)
CROSS JOIN (VALUES (901::bigint),(902::bigint),(903::bigint)) s(supplier_id);

INSERT INTO purchase_receipt_line(receipt_id,line_no,batch_id,warehouse_id,quantity,unit_cost)
SELECT r.receipt_id,
       row_number() OVER (PARTITION BY r.receipt_id ORDER BY s.code)::integer,
       batch.batch_id,w.warehouse_id,
       CASE WHEN b.branch_id=901 THEN s.cloud_qty ELSE s.riverside_qty END,
       s.unit_cost
FROM _sample_consumables s
CROSS JOIN (VALUES (901::bigint,901::bigint),(902::bigint,903::bigint)) b(branch_id,warehouse_id)
JOIN purchase_receipt r ON r.document_no='DEMO-INV-20261123-' || b.branch_id || '-' || s.supplier_id
JOIN stock_item i ON i.code=s.code
JOIN stock_batch batch ON batch.stock_item_id=i.stock_item_id AND batch.batch_no=s.batch_no
JOIN warehouse w ON w.warehouse_id=b.warehouse_id AND w.branch_id=b.branch_id;

INSERT INTO stock_movement(receipt_line_id,warehouse_id,kind,quantity_delta,occurred_at,posted_at,operator_id,evidence,request_key)
SELECT l.receipt_line_id,l.warehouse_id,'采购入库',l.quantity,clock_timestamp(),clock_timestamp(),903,
       '【示例】采购验收 ' || r.document_no,gen_random_uuid()::text
FROM purchase_receipt_line l JOIN purchase_receipt r ON r.receipt_id=l.receipt_id
WHERE r.document_no LIKE 'DEMO-INV-20261123-%';

UPDATE purchase_receipt SET state='已过账' WHERE document_no LIKE 'DEMO-INV-20261123-%';

INSERT INTO audit_log(actor_type,target_type,target_id,operation,before_data,after_data,reason,occurred_at)
SELECT '系统','purchase_receipt',r.receipt_id,'导入虚构库存样例',NULL,
       json_build_object('document_no',r.document_no,'branch_id',r.branch_id,'lines',count(l.receipt_line_id))::text,
       '课程用虚构耗材与账面库存，不代表真实采购、付款或可临床使用的实物',clock_timestamp()
FROM purchase_receipt r JOIN purchase_receipt_line l ON l.receipt_id=r.receipt_id
WHERE r.document_no LIKE 'DEMO-INV-20261123-%'
GROUP BY r.receipt_id;

DO $$
BEGIN
  IF (SELECT count(*) FROM stock_item WHERE code IN (SELECT code FROM _sample_consumables))<>14
     OR (SELECT count(*) FROM stock_batch WHERE batch_no LIKE 'DEMO-INV-B%')<>14
     OR (SELECT count(*) FROM purchase_receipt WHERE document_no LIKE 'DEMO-INV-20261123-%' AND state='已过账')<>6
     OR (SELECT count(*) FROM purchase_receipt_line l JOIN purchase_receipt r ON r.receipt_id=l.receipt_id WHERE r.document_no LIKE 'DEMO-INV-20261123-%')<>28
     OR (SELECT count(*) FROM stock_movement m JOIN purchase_receipt_line l ON l.receipt_line_id=m.receipt_line_id JOIN purchase_receipt r ON r.receipt_id=l.receipt_id WHERE r.document_no LIKE 'DEMO-INV-20261123-%')<>28 THEN
    RAISE EXCEPTION 'Inventory sample counts do not match; rolling back';
  END IF;
END $$;
COMMIT;

SELECT b.name AS branch_name,count(DISTINCT i.stock_item_id) AS stocked_items,
       count(DISTINCT r.receipt_id) AS new_receipts,count(l.receipt_line_id) AS new_lines
FROM purchase_receipt r
JOIN branch b ON b.branch_id=r.branch_id
JOIN purchase_receipt_line l ON l.receipt_id=r.receipt_id
JOIN stock_batch batch ON batch.batch_id=l.batch_id
JOIN stock_item i ON i.stock_item_id=batch.stock_item_id
WHERE r.document_no LIKE 'DEMO-INV-20261123-%'
GROUP BY b.name ORDER BY b.name;
