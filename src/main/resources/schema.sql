DROP TABLE IF EXISTS notification_attempt;
DROP TABLE IF EXISTS blocked_recipient;

CREATE TABLE notification_attempt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    target VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('NEW', 'SENT', 'ERROR', 'UNKNOWN', 'REJECTED')),
    message VARCHAR(255) NOT NULL,
    note VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE notification_attempt IS 'One row per dispatch attempt; the guards keep their state in the process, so these rows are a log, not the state';
COMMENT ON COLUMN notification_attempt.id IS 'Identity, and the ordinal number of the attempt';
COMMENT ON COLUMN notification_attempt.type IS 'EMAIL, SMS, PUSH or whatever arrived';
COMMENT ON COLUMN notification_attempt.target IS 'Recipient: an address, a phone number or a device id';
COMMENT ON COLUMN notification_attempt.payload IS 'The text of the notification';
COMMENT ON COLUMN notification_attempt.status IS 'NEW while the attempt runs, then its outcome';
COMMENT ON COLUMN notification_attempt.message IS 'The last thing said about the attempt: START, the error, or SENT, cut at 255 characters';
COMMENT ON COLUMN notification_attempt.note IS 'What an update attached afterwards; never part of the delivery, so it may change';
COMMENT ON COLUMN notification_attempt.created_at IS 'When the attempt was written, by the database clock, unless the writer set it';
COMMENT ON COLUMN notification_attempt.updated_at IS 'When the row was last written, by the database clock, unless the writer set it';

CREATE TABLE blocked_recipient (
    recipient VARCHAR(255) PRIMARY KEY,
    added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE blocked_recipient IS 'The recipients the service refuses to dispatch to; membership is the whole rule';
COMMENT ON COLUMN blocked_recipient.recipient IS 'The recipient as the request writes it, trimmed and matched exactly';
COMMENT ON COLUMN blocked_recipient.added_at IS 'When the entry was written, by the database clock';
