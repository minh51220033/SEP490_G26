-- One examination ticket per visit; numbers are serialized by branch lock in DispatchService.
-- Preflight: SELECT visit_id, queue_type, COUNT(*) FROM queue_tickets
-- GROUP BY visit_id, queue_type HAVING COUNT(*) > 1;
ALTER TABLE queue_tickets
    ADD CONSTRAINT uk_ft09_visit_queue UNIQUE (visit_id, queue_type),
    ADD COLUMN priority_reason VARCHAR(255) NULL;
