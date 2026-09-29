package com.lumenpost.shipments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.UUID;

@Component
public class CarrierHostClient {

    private final RestClient restClient;

    public CarrierHostClient(@Value("${lumenpost.carrier-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public String createLabel(UUID shipmentId, String route) {
        LabelResponse response = restClient.post()
                .uri("/v1/labels")
                .body(new LabelRequest(shipmentId, route))
                .retrieve()
                .body(LabelResponse.class);
        return Objects.requireNonNull(response, "carrier response").labelRef();
    }

    public void confirmHandoff(UUID shipmentId, String labelRef) {
        restClient.post()
                .uri("/v1/shipments/{shipmentId}/handoff", shipmentId)
                .body(new LabelReference(labelRef))
                .retrieve()
                .toBodilessEntity();
    }

    public void voidLabel(String labelRef) {
        restClient.post()
                .uri("/v1/labels/{labelRef}/void", labelRef)
                .retrieve()
                .toBodilessEntity();
    }

    public void cancelShipment(UUID shipmentId, String labelRef) {
        restClient.post()
                .uri("/v1/shipments/{shipmentId}/cancel", shipmentId)
                .body(new LabelReference(labelRef))
                .retrieve()
                .toBodilessEntity();
    }

    public void requestReturn(UUID shipmentId, String labelRef) {
        restClient.post()
                .uri("/v1/shipments/{shipmentId}/return", shipmentId)
                .body(new LabelReference(labelRef))
                .retrieve()
                .toBodilessEntity();
    }

    public record LabelRequest(UUID shipmentId, String route) {
    }

    public record LabelResponse(String labelRef) {
    }

    public record LabelReference(String labelRef) {
    }
}

@Service
class NotificationService {

    private final RestClient restClient;

    NotificationService(@Value("${lumenpost.notification-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public void send(UUID shipmentId, String template) {
        restClient.post()
                .uri("/v1/notifications")
                .body(new NotificationRequest(shipmentId, template))
                .retrieve()
                .toBodilessEntity();
    }

    record NotificationRequest(UUID shipmentId, String template) {
    }
}
