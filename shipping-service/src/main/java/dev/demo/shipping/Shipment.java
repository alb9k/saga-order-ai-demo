package dev.demo.shipping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "shipments")
public class Shipment {
    @Id
    public UUID orderId;
    @Column(nullable = false)
    public String status;

    protected Shipment() {
    }

    public Shipment(UUID id, String status) {
        this.orderId = id;
        this.status = status;
    }
}
