package dev.demo.shipping;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/internal/shipping")
public class ShippingController {
    private final ShipmentRepository repo;

    public ShippingController(ShipmentRepository repo) {
        this.repo = repo;
    }

    @PostMapping("/{id}/create")
    @Transactional
    public ResponseEntity<Void> execute(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean fail) {
        var existing = repo.findById(id);
        if (existing.isPresent()) {
            if ("ACTIVE".equals(existing.get().status)) return ResponseEntity.ok().build();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already compensated");
        }
        if (fail) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Simulated carrier-error");
        repo.save(new Shipment(id, "ACTIVE"));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<Void> compensate(@PathVariable UUID id) {
        repo.findById(id).ifPresent(e -> {
            e.status = "CANCELLED";
            repo.save(e);
        });
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        return repo.findById(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
