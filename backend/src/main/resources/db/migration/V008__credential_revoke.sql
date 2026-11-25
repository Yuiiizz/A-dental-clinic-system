-- Preserve the three verification states; history distinguishes rejection from revocation.
ALTER TABLE credential_review DROP CONSTRAINT ck_credential_review_action;
ALTER TABLE credential_review ADD CONSTRAINT ck_credential_review_action CHECK(action IN ('submit','verify','reject','revoke'));
ALTER TABLE credential_review DROP CONSTRAINT ck_credential_review_transition;
ALTER TABLE credential_review ADD CONSTRAINT ck_credential_review_transition CHECK (
 (action='submit' AND from_state IS NULL AND to_state='pending') OR
 (action='verify' AND from_state='pending' AND to_state='verified') OR
 (action='reject' AND from_state='pending' AND to_state='rejected') OR
 (action='revoke' AND from_state='verified' AND to_state='rejected'));
COMMENT ON COLUMN credential_review.action IS 'submit、verify、reject、revoke；撤销保留原核验事实';
