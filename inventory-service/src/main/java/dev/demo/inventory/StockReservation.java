package dev.demo.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "stock_reservations")
public class StockReservation {
    @Id
    public UUID orderId;
    @Column(nullable = false)
    public String status;

    protected StockReservation() {
    }

    public StockReservation(UUID id, String status) {
        this.orderId = id;
        this.status = status;
    }
}
