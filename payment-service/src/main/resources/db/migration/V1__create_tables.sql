CREATE TABLE payment_authorizations
(
    order_id UUID PRIMARY KEY,
    status   VARCHAR(32) NOT NULL
);
