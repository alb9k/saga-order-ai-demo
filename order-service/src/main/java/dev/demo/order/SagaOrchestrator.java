package dev.demo.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class SagaOrchestrator {
    private final OrderRepository repo;
    private final OrderCompletionWriter writer;
    private final RestClient client;
    @Value("${services.payment:http://localhost:8082}") String payment;
    @Value("${services.inventory:http://localhost:8083}") String inventory;
    @Value("${services.shipping:http://localhost:8084}") String shipping;
    public SagaOrchestrator(OrderRepository repo, OrderCompletionWriter writer, RestClient.Builder builder) {
        this.repo=repo;this.writer=writer;this.client=builder.build();
    }
    public void start(UUID id) {
        var order=repo.findById(id).orElseThrow();
        List<String> completed=new ArrayList<>();
        String finalStatus;
        String details;
        try {
            step(payment,"payment","authorize",id,order.failureStep);
            completed.add("payment");
            step(inventory,"inventory","reserve",id,order.failureStep);
            completed.add("inventory");
            step(shipping,"shipping","create",id,order.failureStep);
            completed.add("shipping");
            finalStatus="COMPLETED";
            details="All steps completed";
        } catch(Exception ex) {
            List<String> failures=new ArrayList<>();
            Collections.reverse(completed);
            for(String step:completed){
                try { compensate(step,id); }
                catch(Exception c){failures.add(step+": "+c.getMessage());}
            }
            finalStatus=failures.isEmpty()?"CANCELLED":"COMPENSATION_FAILED";
            details="Saga failed: "+ex.getMessage()+"; compensation failures="+failures;
        }
        // One local PostgreSQL transaction saves order status AND outbox event.
        writer.complete(id,finalStatus,details);
    }
    private void step(String host,String service,String action,UUID id,String failAt) {
        client.post().uri(host+"/internal/"+service+"/"+id+"/"+action+"?fail="+service.equals(failAt)).retrieve().toBodilessEntity();
    }
    private void compensate(String service,UUID id) {
        String host=switch(service){
            case "payment"->payment;case "inventory"->inventory;default->shipping;
        };
        String action=switch(service){
            case "payment"->"void";case "inventory"->"release";default->"cancel";
        };
        client.post().uri(host+"/internal/"+service+"/"+id+"/"+action).retrieve().toBodilessEntity();
    }
}
