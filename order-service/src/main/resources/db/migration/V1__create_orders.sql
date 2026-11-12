CREATE TABLE orders
(
    id           UUID PRIMARY KEY,
    status       VARCHAR(40) NOT NULL,
    failure_step VARCHAR(40) NOT NULL,
    details      TEXT        NOT NULL
);
