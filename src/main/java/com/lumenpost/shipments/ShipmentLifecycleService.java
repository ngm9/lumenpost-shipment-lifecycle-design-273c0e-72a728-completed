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
public class ShipmentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentLifecycleService.class);

    private final ShipmentRepository shipmentRepository;
    private final CapacityClient capacityClient;
    private final PaymentClient paymentClient;
    private final CarrierHostClient carrierHostClient;
    private final NotificationService notificationService;

    public ShipmentLifecycleService(ShipmentRepository shipmentRepository,
                                    CapacityClient capacityClient,
                                    PaymentClient paymentClient,
                                    CarrierHostClient carrierHostClient,
                                    NotificationService notificationService) {
        this.shipmentRepository = shipmentRepository;
        this.capacityClient = capacityClient;
        this.paymentClient = paymentClient;
        this.carrierHostClient = carrierHostClient;
        this.notificationService = notificationService;
    }

    @Transactional
    public Shipment create(String route) {
        Shipment shipment = new Shipment(UUID.randomUUID(), "CREATED");
        shipmentRepository.save(shipment);
        boolean held = capacityClient.hold(shipment.getId(), route);
        if (held) {
            changeStatus(shipment, "CAPACITY_HELD");
        } else {
            changeStatus(shipment, "FAILED");
        }
        shipmentRepository.save(shipment);
        if (held) {
            notificationService.send(shipment.getId(), "capacity-held");
        }
        return shipment;
    }

    @Transactional
    public Shipment holdCapacity(UUID shipmentId, String route) {
        Shipment shipment = load(shipmentId);
        if ("DELIVERED".equals(shipment.getStatus())) {
            throw conflict("Delivered shipments cannot reserve capacity");
        }
        if ("CANCELLED".equals(shipment.getStatus())) {
            throw conflict("Cancelled shipments cannot reserve capacity");
        }
        if ("REFUNDED".equals(shipment.getStatus())) {
            throw conflict("Refunded shipments cannot reserve capacity");
        }
        if ("CAPACITY_HELD".equals(shipment.getStatus())) {
            return shipment;
        }
        if (capacityClient.hold(shipmentId, route)) {
            changeStatus(shipment, "CAPACITY_HELD");
        } else {
            changeStatus(shipment, "FAILED");
        }
        return shipmentRepository.save(shipment);
    }

    @Transactional
    public Shipment requestPayment(UUID shipmentId) {
        Shipment shipment = load(shipmentId);
        if ("CREATED".equals(shipment.getStatus())) {
            throw conflict("Capacity has not been held");
        }
        if ("DELIVERED".equals(shipment.getStatus())) {
            throw conflict("Shipment is already delivered");
        }
        if ("CANCELLED".equals(shipment.getStatus())) {
            throw conflict("Shipment is cancelled");
        }
        if ("REFUNDED".equals(shipment.getStatus())) {
            throw conflict("Shipment has been refunded");
        }
        if ("PAYMENT_FAILED".equals(shipment.getStatus())) {
            changeStatus(shipment, "PENDING_PAYMENT");
        } else if ("CAPACITY_HELD".equals(shipment.getStatus())) {
            changeStatus(shipment, "PENDING_PAYMENT");
        } else if ("PAID".equals(shipment.getStatus())) {
            return shipment;
        } else {
            changeStatus(shipment, "PENDING_PAYMENT");
        }
        return shipmentRepository.save(shipment);
    }

    @Transactional
    public Shipment capturePayment(UUID shipmentId, BigDecimal amount) {
        Shipment shipment = load(shipmentId);
        if ("CREATED".equals(shipment.getStatus())) {
            throw conflict("Capacity has not been held");
        }
        if ("DELIVERED".equals(shipment.getStatus())) {
            throw conflict("Delivered shipment cannot be charged");
        }
        if ("CANCELLED".equals(shipment.getStatus())) {
            throw conflict("Cancelled shipment cannot be charged");
        }
        if ("REFUNDED".equals(shipment.getStatus())) {
            throw conflict("Refunded shipment cannot be charged");
        }
        if ("CANCEL_PENDING".equals(shipment.getStatus())) {
            throw conflict("Cancellation is in progress");
        }
        if ("PAID".equals(shipment.getStatus()) && shipment.getPaymentRef() != null) {
            return shipment;
        }
        if ("FAILED".equals(shipment.getStatus())) {
            changeStatus(shipment, "PENDING_PAYMENT");
            shipmentRepository.save(shipment);
        }
        if ("PAYMENT_FAILED".equals(shipment.getStatus())) {
            changeStatus(shipment, "PENDING_PAYMENT");
            shipmentRepository.save(shipment);
        }
        PaymentClient.PaymentResult result = paymentClient.capture(shipment.getPaymentRef(), amount);
        if (result.approved()) {
            shipment.setPaymentRef(result.paymentRef());
            changeStatus(shipment, "PAID");
            shipmentRepository.save(shipment);
            notificationService.send(shipmentId, "payment-received");
        } else {
            changeStatus(shipment, "PAYMENT_FAILED");
            shipmentRepository.save(shipment);
        }
        return shipment;
    }

    @Transactional
    public Shipment requestLabel(UUID shipmentId, String route) {
        Shipment shipment = load(shipmentId);
        if ("CREATED".equals(shipment.getStatus())) {
            throw conflict("Shipment is not ready for a label");
        }
        if ("CAPACITY_HELD".equals(shipment.getStatus())) {
            throw conflict("Payment has not completed");
        }
        if ("PENDING_PAYMENT".equals(shipment.getStatus())) {
            throw conflict("Payment is still pending");
        }
        if ("PAYMENT_FAILED".equals(shipment.getStatus())) {
            throw conflict("Payment failed");
        }
        if ("CANCELLED".equals(shipment.getStatus())) {
            throw conflict("Shipment is cancelled");
        }
        if ("REFUNDED".equals(shipment.getStatus())) {
            throw conflict("Shipment is refunded");
        }
        if ("LABEL_CREATED".equals(shipment.getStatus()) && shipment.getLabelRef() != null) {
            return shipment;
        }
        changeStatus(shipment, "LABEL_REQUESTED");
        shipmentRepository.save(shipment);
        String labelRef = carrierHostClient.createLabel(shipmentId, route);
        shipment.setLabelRef(labelRef);
        changeStatus(shipment, "LABEL_CREATED");
        shipmentRepository.save(shipment);
        notificationService.send(shipmentId, "label-created");
        return shipment;
    }

    @Transactional
    public Shipment handoff(UUID shipmentId) {
        Shipment shipment = load(shipmentId);
        if (shipment.getLabelRef() == null) {
            throw conflict("Shipment has no carrier label");
        }
        if ("CANCELLED".equals(shipment.getStatus())) {
            throw conflict("Shipment is cancelled");
        }
        if ("DELIVERED".equals(shipment.getStatus())) {
            return shipment;
        }
        if ("HANDED_OFF".equals(shipment.getStatus())) {
            return shipment;
        }
        carrierHostClient.confirmHandoff(shipmentId, shipment.getLabelRef());
        changeStatus(shipment, "HANDED_OFF");
        shipmentRepository.save(shipment);
        notificationService.send(shipmentId, "carrier-handoff");
        return shipment;
    }

    @Transactional
    public Shipment markInTransit(UUID shipmentId) {
        Shipment shipment = load(shipmentId);
        if ("CANCELLED".equals(shipment.getStatus())) {
            throw conflict("Shipment is cancelled");
        }
        if ("DELIVERED".equals(shipment.getStatus())) {
            return shipment;
        }
        if ("REFUNDED".equals(shipment.getStatus())) {
            throw conflict("Shipment is refunded");
        }
        changeStatus(shipment, "IN_TRANSIT");
        return shipmentRepository.save(shipment);
    }

    @Transactional
    public Shipment deliver(UUID shipmentId) {
        Shipment shipment = load(shipmentId);
        if ("CANCELLED".equals(shipment.getStatus())) {
            throw conflict("Shipment is cancelled");
        }
        if ("REFUNDED".equals(shipment.getStatus())) {
            throw conflict("Shipment is refunded");
        }
        if ("DELIVERED".equals(shipment.getStatus())) {
            return shipment;
        }
        changeStatus(shipment, "DELIVERED");
        shipmentRepository.save(shipment);
        notificationService.send(shipmentId, "delivered");
        return shipment;
    }

    @Transactional
    public Shipment cancel(UUID shipmentId, String reason) {
        Shipment shipment = load(shipmentId);
        String status = shipment.getStatus();
        if ("DELIVERED".equals(status)) {
            throw conflict("Delivered shipment cannot be cancelled");
        }
        if ("CANCELLED".equals(status)) {
            return shipment;
        }
        if ("REFUNDED".equals(status)) {
            changeStatus(shipment, "CANCELLED");
            return shipmentRepository.save(shipment);
        }
        changeStatus(shipment, "CANCEL_PENDING");
        shipmentRepository.save(shipment);
        if ("CREATED".equals(status)) {
            changeStatus(shipment, "CANCELLED");
        }
        if ("CAPACITY_HELD".equals(status)) {
            capacityClient.release(shipmentId);
            changeStatus(shipment, "CANCELLED");
        }
        if ("PENDING_PAYMENT".equals(status)) {
            capacityClient.release(shipmentId);
            changeStatus(shipment, "CANCELLED");
        }
        if ("PAYMENT_FAILED".equals(status)) {
            capacityClient.release(shipmentId);
            changeStatus(shipment, "CANCELLED");
        }
        if ("PAID".equals(status)) {
            paymentClient.refund(shipment.getPaymentRef(), BigDecimal.ZERO, reason);
            capacityClient.release(shipmentId);
            changeStatus(shipment, "CANCELLED");
        }
        if ("LABEL_REQUESTED".equals(status)) {
            paymentClient.refund(shipment.getPaymentRef(), BigDecimal.ZERO, reason);
            capacityClient.release(shipmentId);
            changeStatus(shipment, "CANCELLED");
        }
        if ("LABEL_CREATED".equals(status)) {
            carrierHostClient.voidLabel(shipment.getLabelRef());
            paymentClient.refund(shipment.getPaymentRef(), BigDecimal.ZERO, reason);
            capacityClient.release(shipmentId);
            changeStatus(shipment, "CANCELLED");
        }
        if ("HANDED_OFF".equals(status)) {
            carrierHostClient.cancelShipment(shipmentId, shipment.getLabelRef());
            paymentClient.refund(shipment.getPaymentRef(), BigDecimal.ZERO, reason);
            changeStatus(shipment, "CANCELLED");
        }
        if ("IN_TRANSIT".equals(status)) {
            carrierHostClient.requestReturn(shipmentId, shipment.getLabelRef());
            changeStatus(shipment, "CANCELLED");
        }
        if ("FAILED".equals(status)) {
            capacityClient.release(shipmentId);
            changeStatus(shipment, "CANCELLED");
        }
        if ("REFUND_PENDING".equals(status)) {
            changeStatus(shipment, "CANCELLED");
        }
        shipmentRepository.save(shipment);
        notificationService.send(shipmentId, "cancelled");
        return shipment;
    }

    public Shipment failAndRelease(UUID shipmentId, String reason) {
        Shipment shipment = load(shipmentId);
        if ("DELIVERED".equals(shipment.getStatus())) {
            throw conflict("Delivered shipment cannot fail");
        }
        if ("CANCELLED".equals(shipment.getStatus())) {
            return shipment;
        }
        if ("REFUNDED".equals(shipment.getStatus())) {
            return shipment;
        }
        if (shipment.getLabelRef() != null) {
            carrierHostClient.voidLabel(shipment.getLabelRef());
        }
        changeStatus(shipment, "FAILED");
        shipmentRepository.save(shipment);
        capacityClient.release(shipmentId);
        notificationService.send(shipmentId, reason == null ? "shipment-failed" : reason);
        return shipment;
    }

    public Shipment releaseCapacity(UUID shipmentId) {
        Shipment shipment = load(shipmentId);
        if ("DELIVERED".equals(shipment.getStatus())) {
            return shipment;
        }
        if ("IN_TRANSIT".equals(shipment.getStatus())) {
            return shipment;
        }
        if ("HANDED_OFF".equals(shipment.getStatus())) {
            return shipment;
        }
        capacityClient.release(shipmentId);
        if (!"CANCELLED".equals(shipment.getStatus())) {
            changeStatus(shipment, "FAILED");
            shipmentRepository.save(shipment);
        }
        return shipment;
    }

    @Transactional
    public Shipment retryPayment(UUID shipmentId, BigDecimal amount) {
        Shipment shipment = load(shipmentId);
        if ("PAYMENT_FAILED".equals(shipment.getStatus())) {
            changeStatus(shipment, "PENDING_PAYMENT");
            shipmentRepository.save(shipment);
        }
        if ("CAPACITY_HELD".equals(shipment.getStatus())) {
            changeStatus(shipment, "PENDING_PAYMENT");
            shipmentRepository.save(shipment);
        }
        if ("PAID".equals(shipment.getStatus()) && shipment.getPaymentRef() != null) {
            return shipment;
        }
        if ("CANCELLED".equals(shipment.getStatus())) {
            throw conflict("Shipment is cancelled");
        }
        PaymentClient.PaymentResult result = paymentClient.capture(shipment.getPaymentRef(), amount);
        if (result.approved()) {
            shipment.setPaymentRef(result.paymentRef());
            changeStatus(shipment, "PAID");
        } else {
            changeStatus(shipment, "PAYMENT_FAILED");
        }
        return shipmentRepository.save(shipment);
    }

    @Transactional
    public Shipment voidLabel(UUID shipmentId) {
        Shipment shipment = load(shipmentId);
        if (shipment.getLabelRef() == null) {
            return shipment;
        }
        if ("DELIVERED".equals(shipment.getStatus())) {
            throw conflict("Delivered label cannot be voided");
        }
        if ("IN_TRANSIT".equals(shipment.getStatus())) {
            throw conflict("In-transit label cannot be voided");
        }
        carrierHostClient.voidLabel(shipment.getLabelRef());
        shipment.setLabelRef(null);
        if (shipment.getPaymentRef() == null) {
            changeStatus(shipment, "CAPACITY_HELD");
        } else {
            changeStatus(shipment, "PAID");
        }
        return shipmentRepository.save(shipment);
    }

    @Transactional
    public Shipment resetToHeld(UUID shipmentId) {
        Shipment shipment = load(shipmentId);
        if ("DELIVERED".equals(shipment.getStatus())) {
            throw conflict("Delivered shipment cannot be reset");
        }
        if ("IN_TRANSIT".equals(shipment.getStatus())) {
            throw conflict("In-transit shipment cannot be reset");
        }
        changeStatus(shipment, "CAPACITY_HELD");
        return shipmentRepository.save(shipment);
    }

    private Shipment load(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
    }

    private void changeStatus(Shipment shipment, String nextStatus) {
        String previous = shipment.getStatus();
        shipment.setStatus(nextStatus);
        log.info("ShipmentLifecycleService shipment={} status {} -> {}", shipment.getId(), previous, nextStatus);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
