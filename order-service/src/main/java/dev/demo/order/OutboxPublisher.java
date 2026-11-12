package dev.demo.order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
@Component
public class OutboxPublisher {
 private final OutboxRepository repository;
 private final KafkaTemplate<String,String> kafka;
 private final TransactionTemplate tx;
 @Value("${outbox.topic:saga-order-events}") String topic;
 public OutboxPublisher(OutboxRepository repository,KafkaTemplate<String,String> kafka,
                        org.springframework.transaction.PlatformTransactionManager manager){
  this.repository=repository;this.kafka=kafka;this.tx=new TransactionTemplate(manager);
 }
 @Scheduled(fixedDelayString="${outbox.poll-ms:2000}")
 public void publish(){
  // Short batch; row locks prevent concurrent publishers taking the same event.
  // Kafka acknowledgement is awaited before marking published.
  tx.executeWithoutResult(status -> {
   for(var e:repository.lockPending(20)){
    try{
     kafka.send(topic,e.aggregateId.toString(),e.payload).get();
     repository.markPublished(e.id,Instant.now());
    }catch(Exception ex){
     // Keep pending for retry; a duplicate is possible if Kafka ack precedes DB commit.
     org.slf4j.LoggerFactory.getLogger(getClass()).warn("Outbox delivery deferred for {}",e.id,ex);
     break;
    }
   }
  });
 }
}
