package com.lumenpost.shipments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

@Service
public class PaymentCallbackService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackService.class);
    private final ShipmentRepository shipmentRepository;

    public PaymentCallbackService(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Transactional
    public Shipment handle(UUID shipmentId, String event, String paymentRef) {
        Shipment shipment = load(shipmentId);
        String previous = shipment.getStatus();
        switch (event.toUpperCase(Locale.ROOT)) {
            case "AUTHORIZED" -> {
                shipment.setPaymentRef(paymentRef);
                shipment.setStatus("PENDING_PAYMENT");
            }
            case "CAPTURED", "SETTLED" -> {
                shipment.setPaymentRef(paymentRef);
                shipment.setStatus("PAID");
            }
            case "DECLINED", "FAILED" -> shipment.setStatus("PAYMENT_FAILED");
            case "REFUND_REQUESTED" -> shipment.setStatus("REFUND_PENDING");
            case "REFUNDED" -> shipment.setStatus("REFUNDED");
            case "VOIDED" -> {
                shipment.setPaymentRef(null);
                shipment.setStatus("CAPACITY_HELD");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown payment event");
        }
        log.info("PaymentCallbackService shipment={} status {} -> {}", shipmentId, previous, shipment.getStatus());
        return shipmentRepository.save(shipment);
    }

    private Shipment load(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
    }
}

@Service
class CarrierCallbackService {

    private static final Logger log = LoggerFactory.getLogger(CarrierCallbackService.class);
    private final ShipmentRepository shipmentRepository;

    CarrierCallbackService(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Transactional
    public Shipment handle(UUID shipmentId, String event, String labelRef) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
        String previous = shipment.getStatus();
        switch (event.toUpperCase(Locale.ROOT)) {
            case "LABEL_CREATED" -> {
                shipment.setLabelRef(labelRef);
                shipment.setStatus("LABEL_CREATED");
            }
            case "ACCEPTED", "HANDED_OFF" -> {
                if (labelRef != null) {
                    shipment.setLabelRef(labelRef);
                }
                shipment.setStatus("HANDED_OFF");
            }
            case "IN_TRANSIT" -> shipment.setStatus("IN_TRANSIT");
            case "DELIVERED" -> shipment.setStatus("DELIVERED");
            case "LABEL_VOIDED" -> {
                shipment.setLabelRef(null);
                shipment.setStatus(shipment.getPaymentRef() == null ? "CAPACITY_HELD" : "PAID");
            }
            case "REJECTED", "LOST" -> shipment.setStatus("FAILED");
            case "RETURNED" -> shipment.setStatus("CANCELLED");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown carrier event");
        }
        log.info("CarrierCallbackService shipment={} status {} -> {}", shipmentId, previous, shipment.getStatus());
        return shipmentRepository.save(shipment);
    }
}
