DROP TABLE IF EXISTS notification_attempt;

CREATE TABLE notification_attempt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    target VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('NEW', 'SENT', 'ERROR', 'UNKNOWN', 'REJECTED')),
    message VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE notification_attempt IS 'One row per dispatch attempt; the state of the service is a query over these rows';
COMMENT ON COLUMN notification_attempt.id IS 'Identity, and the ordinal number of the attempt';
COMMENT ON COLUMN notification_attempt.type IS 'EMAIL, SMS, PUSH or whatever arrived';
COMMENT ON COLUMN notification_attempt.target IS 'Recipient: an address, a phone number or a device id';
COMMENT ON COLUMN notification_attempt.payload IS 'The text of the notification';
COMMENT ON COLUMN notification_attempt.status IS 'NEW while the attempt runs, then its outcome';
COMMENT ON COLUMN notification_attempt.message IS 'The last thing said about the attempt: START, the error, or SENT, cut at 255 characters';
COMMENT ON COLUMN notification_attempt.created_at IS 'When the attempt was written, by the database clock';
COMMENT ON COLUMN notification_attempt.updated_at IS 'When the outcome was written, by the database clock';

CREATE INDEX ix_notification_attempt_target ON notification_attempt (target, created_at);
CREATE INDEX ix_notification_attempt_status ON notification_attempt (status, id);
