package dev.demo.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "payment_authorizations")
public class PaymentAuthorization {
    @Id
    public UUID orderId;
    @Column(nullable = false)
    public String status;

    protected PaymentAuthorization() {
    }

    public PaymentAuthorization(UUID id, String status) {
        this.orderId = id;
        this.status = status;
    }
}
