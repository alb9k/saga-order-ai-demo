package dev.demo.order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
@Service
public class OrderCompletionWriter {
 private final OrderRepository orders;
 private final OutboxRepository outbox;
 public OrderCompletionWriter(OrderRepository orders,OutboxRepository outbox){
  this.orders=orders;this.outbox=outbox;
 }
 @Transactional
 public void complete(UUID id,String status,String details){
  var order=orders.findById(id).orElseThrow();
  order.status=status;order.details=details;
  orders.save(order);
  // Preserve the existing Kafka event contract: "<orderId>|<status>".
  outbox.save(new OutboxEvent(id,"OrderStatusChanged",id+"|"+status));
 }
}
