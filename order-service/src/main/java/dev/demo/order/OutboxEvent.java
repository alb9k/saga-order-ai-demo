package dev.demo.order;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="outbox_events")
public class OutboxEvent {
 @Id public UUID id;
 @Column(name="aggregate_id",nullable=false) public UUID aggregateId;
 @Column(name="event_type",nullable=false) public String eventType;
 @Column(nullable=false,columnDefinition="TEXT") public String payload;
 @Column(name="created_at",nullable=false) public Instant createdAt;
 @Column(name="published_at") public Instant publishedAt;
 protected OutboxEvent(){}
 public OutboxEvent(UUID aggregateId,String eventType,String payload){
  this.id=UUID.randomUUID();this.aggregateId=aggregateId;this.eventType=eventType;
  this.payload=payload;this.createdAt=Instant.now();
 }
}
