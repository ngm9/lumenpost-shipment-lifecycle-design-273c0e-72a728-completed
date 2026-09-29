package com.lumenpost.shipments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class RerouteService {

    private static final Logger log = LoggerFactory.getLogger(RerouteService.class);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentLifecycleService lifecycleService;
    private final CapacityClient capacityClient;
    private final CarrierHostClient carrierHostClient;

    public RerouteService(ShipmentRepository shipmentRepository,
                          ShipmentLifecycleService lifecycleService,
                          CapacityClient capacityClient,
                          CarrierHostClient carrierHostClient) {
        this.shipmentRepository = shipmentRepository;
        this.lifecycleService = lifecycleService;
        this.capacityClient = capacityClient;
        this.carrierHostClient = carrierHostClient;
    }

    @Transactional
    public Shipment reroute(UUID shipmentId, String newRoute, boolean restartPayment) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
        String previous = shipment.getStatus();
        if ("DELIVERED".equals(previous)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivered shipment cannot be rerouted");
        }
        if ("CANCELLED".equals(previous)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled shipment cannot be rerouted");
        }
        if ("IN_TRANSIT".equals(previous) && shipment.getLabelRef() != null) {
            carrierHostClient.requestReturn(shipmentId, shipment.getLabelRef());
        }
        if (shipment.getLabelRef() != null) {
            carrierHostClient.voidLabel(shipment.getLabelRef());
            shipment.setLabelRef(null);
        }
        capacityClient.release(shipmentId);
        boolean held = capacityClient.hold(shipmentId, newRoute);
        if (!held) {
            shipment.setStatus("FAILED");
            return shipmentRepository.save(shipment);
        }
        shipment.setStatus("CAPACITY_HELD");
        shipmentRepository.save(shipment);
        log.info("RerouteService shipment={} status {} -> CAPACITY_HELD reason=route_refresh", shipmentId, previous);
        if (restartPayment || "PENDING_PAYMENT".equals(previous) || "PAYMENT_FAILED".equals(previous)) {
            return lifecycleService.requestPayment(shipmentId);
        }
        if ("LABEL_REQUESTED".equals(previous) || "LABEL_CREATED".equals(previous) || "HANDED_OFF".equals(previous)) {
            if (shipment.getPaymentRef() != null) {
                shipment.setStatus("PAID");
                shipmentRepository.save(shipment);
                return lifecycleService.requestLabel(shipmentId, newRoute);
            }
        }
        return shipment;
    }
}
