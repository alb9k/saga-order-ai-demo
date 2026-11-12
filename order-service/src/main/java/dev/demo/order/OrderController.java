package dev.demo.order;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderRepository repo;
    private final SagaOrchestrator saga;

    public OrderController(OrderRepository repo, SagaOrchestrator saga) {
        this.repo = repo;
        this.saga = saga;
    }

    public record CreateOrder(String failAt) {
    }

    @PostMapping
    public ResponseEntity<OrderEntity> create(@RequestBody(required = false) CreateOrder req) {
        String failAt = req == null || req.failAt() == null ? "none" : req.failAt();
        if (!Set.of("none", "payment", "inventory", "shipping").contains(failAt))
            return ResponseEntity.badRequest().build();
        var order = repo.save(new OrderEntity(UUID.randomUUID(), failAt));
        saga.start(order.id);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.findById(order.id).orElseThrow());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderEntity> get(@PathVariable UUID id) {
        return repo.findById(id).<ResponseEntity<OrderEntity>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
