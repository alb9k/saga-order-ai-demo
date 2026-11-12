package dev.demo.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "orders")
public class OrderEntity {
    @Id
    public UUID id;
    @Column(nullable = false)
    public String status;
    @Column(nullable = false)
    public String failureStep;
    @Column(nullable = false)
    public String details;

    protected OrderEntity() {
    }

    public OrderEntity(UUID id, String failureStep) {
        this.id = id;
        this.failureStep = failureStep;
        this.status = "PENDING";
        this.details = "";
    }
}
