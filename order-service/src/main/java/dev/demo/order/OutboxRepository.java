package dev.demo.order;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
public interface OutboxRepository extends JpaRepository<OutboxEvent,UUID> {
 @Query(value="SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY created_at, id LIMIT :batch FOR UPDATE SKIP LOCKED",nativeQuery=true)
 List<OutboxEvent> lockPending(@Param("batch") int batch);
 @Modifying
 @Query("UPDATE OutboxEvent e SET e.publishedAt = :at WHERE e.id = :id AND e.publishedAt IS NULL")
 int markPublished(@Param("id") UUID id,@Param("at") Instant at);
}
