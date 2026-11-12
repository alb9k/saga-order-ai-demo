CREATE TABLE outbox_events (
 id UUID PRIMARY KEY,
 aggregate_id UUID NOT NULL,
 event_type VARCHAR(80) NOT NULL,
 payload TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 published_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;
