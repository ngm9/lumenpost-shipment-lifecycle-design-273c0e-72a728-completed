package com.lumenpost.shipments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.UUID;

@Component
public class CapacityClient {

    private final RestClient restClient;

    public CapacityClient(@Value("${lumenpost.capacity-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean hold(UUID shipmentId, String route) {
        HoldResponse response = restClient.post()
                .uri("/v1/capacity/holds")
                .body(new HoldRequest(shipmentId, route))
                .retrieve()
                .body(HoldResponse.class);
        return Objects.requireNonNull(response, "capacity response").held();
    }

    public void release(UUID shipmentId) {
        restClient.post()
                .uri("/v1/capacity/holds/{shipmentId}/release", shipmentId)
                .retrieve()
                .toBodilessEntity();
    }

    public record HoldRequest(UUID shipmentId, String route) {
    }

    public record HoldResponse(boolean held, String holdRef) {
    }
}
