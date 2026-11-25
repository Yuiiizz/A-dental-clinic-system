-- Doctor unavailability resolution audit fields. Existing resource_unavailable already has
-- the full resolution group; dentist_unavailable only had state, so add the missing group
-- without changing V022 or any historical migration.
ALTER TABLE dentist_unavailable
    ADD COLUMN resolved_at timestamptz,
    ADD COLUMN resolved_by bigint,
    ADD COLUMN resolve_basis varchar(300);

ALTER TABLE dentist_unavailable
    ADD CONSTRAINT fk_dentist_unavailable_resolved_by
    FOREIGN KEY (resolved_by) REFERENCES staff (staff_id) ON DELETE RESTRICT;

ALTER TABLE dentist_unavailable
    ADD CONSTRAINT ck_dentist_unavailability_resolution
    CHECK (
      (state='有效' AND num_nonnulls(resolved_at,resolved_by,resolve_basis)=0)
      OR (state='已撤销' AND num_nonnulls(resolved_at,resolved_by,resolve_basis)=3
        AND btrim(resolve_basis)<>'' AND resolved_at>=recorded_at)
    ) NOT VALID;

CREATE INDEX ix_dentist_unavailable_resolved_by ON dentist_unavailable(resolved_by);
