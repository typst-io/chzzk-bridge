-- https://www.sqlite.org/datatype3.html
CREATE TABLE token
(
    token_channel_id         TEXT    NOT NULL PRIMARY KEY,
    token_mc_uuid            TEXT    NOT NULL,
    token_access_token       TEXT    NOT NULL,
    token_refresh_token      TEXT    NOT NULL,
    token_expire_time        BIGINT  NOT NULL,
    token_last_sent_event_id INTEGER NOT NULL DEFAULT 0,
    UNIQUE (token_mc_uuid)
);
CREATE INDEX idx_token_mc_uuid ON token (token_mc_uuid);

CREATE TABLE event
(
    event_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    event_channel_id  TEXT    NOT NULL,
    event_sender_id   TEXT    NOT NULL,
    event_sender_name TEXT    NOT NULL,
    event_message     TEXT    NOT NULL,
    event_time        BIGINT  NOT NULL,
    event_pay_amount  INTEGER NOT NULL DEFAULT 0
);