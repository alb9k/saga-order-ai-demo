package dev.demo.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@RestController
public class OrderAssistantController {
    private static final Set<String> SIMULATION_STEPS = Set.of("payment", "inventory", "shipping");
    private static final Set<String> ORDER_STATUSES =
            Set.of("PENDING", "COMPLETED", "CANCELLED", "COMPENSATION_FAILED");

    private final ChatClient assistant;
    private final RestClient orders;

    OrderAssistantController(ChatClient.Builder assistant, @Value("${orders.base-url}") String baseUrl) {
        this.assistant = assistant.build();
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.orders = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @GetMapping("/assistant/orders/{id}")
    Explanation explain(@PathVariable UUID id) {
        var tool = new OrderTool(id);
        String answer;
        try {
            answer = assistant.prompt()
                    .system("""
                            Spiega in italiano e in massimo quattro frasi lo stato dell'ordine.
                            Usa getOrderStatus prima di rispondere. Basa la risposta sui dati del tool.
                            simulatedFailureStep indica soltanto il guasto configurato per il test.
                            Se l'ordine è annullato, indica che la causa effettiva non è disponibile.
                            Con COMPENSATION_FAILED segnala che almeno una compensazione è fallita.
                            Non inventare pagamenti, spedizioni, rimborsi o cause.
                            """)
                    .user("Che cosa è successo a questo ordine?")
                    .tools(tool)
                    .call()
                    .content();
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Chiamata al modello AI fallita");
        }

        if (tool.result == null || tool.result.outcome() == Outcome.UNAVAILABLE) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stato ordine non disponibile");
        }
        if (tool.result.outcome() == Outcome.NOT_FOUND) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ordine non trovato");
        }
        if (answer == null || answer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Risposta AI vuota");
        }
        return new Explanation(id, tool.result.status(), tool.result.simulatedFailureStep(), answer);
    }

    public record Explanation(UUID orderId, String status, String simulatedFailureStep, String answer) {}
    public record OrderData(String status, String failureStep) {}
    public record OrderSnapshot(Outcome outcome, String status, String simulatedFailureStep) {}
    public enum Outcome { FOUND, NOT_FOUND, UNAVAILABLE }

    public final class OrderTool {
        private final UUID id;
        private OrderSnapshot result;

        OrderTool(UUID id) {
            this.id = id;
        }

        @Tool(description = "Consulta lo stato dell'ordine associato alla richiesta, in sola lettura")
        public synchronized OrderSnapshot getOrderStatus() {
            if (result == null) {
                result = readOrder();
            }
            return result;
        }

        private OrderSnapshot readOrder() {
            try {
                var data = orders.get().uri("/orders/{id}", id).retrieve().body(OrderData.class);
                if (data == null || data.status() == null || !ORDER_STATUSES.contains(data.status())) {
                    return new OrderSnapshot(Outcome.UNAVAILABLE, null, null);
                }
                var step = data.failureStep();
                var simulation = step != null && SIMULATION_STEPS.contains(step) ? step : null;
                return new OrderSnapshot(Outcome.FOUND, data.status(), simulation);
            } catch (RestClientResponseException ex) {
                var outcome = ex.getStatusCode().value() == 404 ? Outcome.NOT_FOUND : Outcome.UNAVAILABLE;
                return new OrderSnapshot(outcome, null, null);
            } catch (RestClientException ex) {
                return new OrderSnapshot(Outcome.UNAVAILABLE, null, null);
            }
        }
    }
}
