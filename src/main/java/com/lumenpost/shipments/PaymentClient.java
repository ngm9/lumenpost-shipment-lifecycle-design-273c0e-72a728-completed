package com.lumenpost.shipments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Objects;

@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);
    private final RestClient restClient;

    public PaymentClient(@Value("${lumenpost.payment-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public PaymentResult capture(String existingPaymentRef, BigDecimal amount) {
        PaymentResult result = restClient.post()
                .uri("/v1/payments/capture")
                .body(new CaptureRequest(existingPaymentRef, amount))
                .retrieve()
                .body(PaymentResult.class);
        PaymentResult required = Objects.requireNonNull(result, "payment response");
        if (required.approved()) {
            log.info("PaymentClient capture succeeded payment={} amount={}", required.paymentRef(), amount);
        }
        return required;
    }

    public RefundResult refund(String paymentRef, BigDecimal amount, String reason) {
        RefundResult result = restClient.post()
                .uri("/v1/payments/{paymentRef}/refund", paymentRef)
                .body(new RefundRequest(amount, reason))
                .retrieve()
                .body(RefundResult.class);
        return Objects.requireNonNull(result, "refund response");
    }

    public record CaptureRequest(String paymentRef, BigDecimal amount) {
    }

    public record PaymentResult(boolean approved, String paymentRef, String declineReason) {
    }

    public record RefundRequest(BigDecimal amount, String reason) {
    }

    public record RefundResult(boolean refunded, String refundRef) {
    }
}
