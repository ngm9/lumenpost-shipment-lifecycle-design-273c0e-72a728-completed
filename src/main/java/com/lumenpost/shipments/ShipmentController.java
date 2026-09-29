package com.lumenpost.shipments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

@SpringBootApplication
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentLifecycleService lifecycleService;
    private final ShipmentRepository shipmentRepository;
    private final PaymentCallbackService paymentCallbackService;
    private final CarrierCallbackService carrierCallbackService;
    private final RerouteService rerouteService;
    private final RefundService refundService;

    public ShipmentController(ShipmentLifecycleService lifecycleService,
                              ShipmentRepository shipmentRepository,
                              PaymentCallbackService paymentCallbackService,
                              CarrierCallbackService carrierCallbackService,
                              RerouteService rerouteService,
                              RefundService refundService) {
        this.lifecycleService = lifecycleService;
        this.shipmentRepository = shipmentRepository;
        this.paymentCallbackService = paymentCallbackService;
        this.carrierCallbackService = carrierCallbackService;
        this.rerouteService = rerouteService;
        this.refundService = refundService;
    }

    public static void main(String[] args) {
        SpringApplication.run(ShipmentController.class, args);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Shipment create(@Valid @RequestBody CreateShipmentRequest request) {
        return lifecycleService.create(request.route());
    }

    @GetMapping("/{shipmentId}")
    public Shipment get(@PathVariable UUID shipmentId) {
        return shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
    }

    @PostMapping("/{shipmentId}/commands/{command}")
    public Shipment command(@PathVariable UUID shipmentId,
                            @PathVariable String command,
                            @RequestBody(required = false) LifecycleCommand request) {
        LifecycleCommand body = request == null ? new LifecycleCommand(null, null, null) : request;
        return switch (command.toUpperCase(Locale.ROOT)) {
            case "HOLD_CAPACITY" -> lifecycleService.holdCapacity(shipmentId, requiredRoute(body.route()));
            case "REQUEST_PAYMENT" -> lifecycleService.requestPayment(shipmentId);
            case "CAPTURE_PAYMENT" -> lifecycleService.capturePayment(shipmentId, requiredAmount(body.amount()));
            case "REQUEST_LABEL" -> lifecycleService.requestLabel(shipmentId, requiredRoute(body.route()));
            case "HANDOFF" -> lifecycleService.handoff(shipmentId);
            case "IN_TRANSIT" -> lifecycleService.markInTransit(shipmentId);
            case "DELIVER" -> lifecycleService.deliver(shipmentId);
            case "CANCEL" -> lifecycleService.cancel(shipmentId, body.reason());
            case "FAIL_AND_RELEASE" -> lifecycleService.failAndRelease(shipmentId, body.reason());
            case "RELEASE_CAPACITY" -> lifecycleService.releaseCapacity(shipmentId);
            case "RETRY_PAYMENT" -> lifecycleService.retryPayment(shipmentId, requiredAmount(body.amount()));
            case "VOID_LABEL" -> lifecycleService.voidLabel(shipmentId);
            case "RESET_TO_HELD" -> lifecycleService.resetToHeld(shipmentId);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown lifecycle command");
        };
    }

    @PostMapping("/{shipmentId}/callbacks/payment")
    public Shipment paymentCallback(@PathVariable UUID shipmentId,
                                    @Valid @RequestBody PaymentCallback request) {
        return paymentCallbackService.handle(shipmentId, request.event(), request.paymentRef());
    }

    @PostMapping("/{shipmentId}/callbacks/carrier")
    public Shipment carrierCallback(@PathVariable UUID shipmentId,
                                    @Valid @RequestBody CarrierCallback request) {
        return carrierCallbackService.handle(shipmentId, request.event(), request.labelRef());
    }

    @PostMapping("/{shipmentId}/reroute")
    public Shipment reroute(@PathVariable UUID shipmentId, @Valid @RequestBody RerouteRequest request) {
        return rerouteService.reroute(shipmentId, request.route(), request.restartPayment());
    }

    @PostMapping("/{shipmentId}/refund")
    public Shipment refund(@PathVariable UUID shipmentId, @Valid @RequestBody RefundRequest request) {
        return refundService.refund(shipmentId, request.amount(), request.reason());
    }

    private static String requiredRoute(String route) {
        if (route == null || route.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "route is required");
        }
        return route;
    }

    private static BigDecimal requiredAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "positive amount is required");
        }
        return amount;
    }

    public record CreateShipmentRequest(@NotBlank String route) {
    }

    public record LifecycleCommand(BigDecimal amount, String route, String reason) {
    }

    public record PaymentCallback(@NotBlank String event, String paymentRef) {
    }

    public record CarrierCallback(@NotBlank String event, String labelRef) {
    }

    public record RerouteRequest(@NotBlank String route, boolean restartPayment) {
    }

    public record RefundRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, String reason) {
    }
}
