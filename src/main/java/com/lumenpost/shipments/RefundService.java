package com.lumenpost.shipments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final ShipmentRepository shipmentRepository;
    private final PaymentClient paymentClient;
    private final CapacityClient capacityClient;

    public RefundService(ShipmentRepository shipmentRepository,
                         PaymentClient paymentClient,
                         CapacityClient capacityClient) {
        this.shipmentRepository = shipmentRepository;
        this.paymentClient = paymentClient;
        this.capacityClient = capacityClient;
    }

    @Transactional
    public Shipment refund(UUID shipmentId, BigDecimal amount, String reason) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
        if (shipment.getPaymentRef() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipment has no payment");
        }
        if ("REFUNDED".equals(shipment.getStatus())) {
            return shipment;
        }
        String previous = shipment.getStatus();
        shipment.setStatus("REFUND_PENDING");
        shipmentRepository.save(shipment);
        PaymentClient.RefundResult result = paymentClient.refund(shipment.getPaymentRef(), amount, reason);
        if (result.refunded()) {
            capacityClient.release(shipmentId);
            shipment.setStatus("REFUNDED");
        } else {
            shipment.setStatus(previous);
        }
        log.info("RefundService shipment={} status {} -> {} refund={}",
                shipmentId, previous, shipment.getStatus(), result.refundRef());
        return shipmentRepository.save(shipment);
    }

    @Transactional
    public Shipment refundAndCancel(UUID shipmentId, BigDecimal amount, String reason) {
        Shipment shipment = refund(shipmentId, amount, reason);
        if ("REFUNDED".equals(shipment.getStatus())) {
            capacityClient.release(shipmentId);
            shipment.setStatus("CANCELLED");
            shipmentRepository.save(shipment);
        }
        return shipment;
    }
}
