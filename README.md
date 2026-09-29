# Lumenpost Shipment Lifecycle Design

The application runs on Java 21, Spring Boot 3.3, Spring MVC, Spring Data JPA, and PostgreSQL 15. ShipmentController delegates lifecycle requests to a 2,600-line ShipmentLifecycleService containing more than 40 branches over a mutable String status on the Shipment JPA entity.

This repository is the Java implementation of that system.

## Helpful Tips

- Read `docs/DESIGN.md` — the design document — for the full background on this task.
- Review the source code below to see exactly how the current system works.
- On the Utkrusht whiteboard for this task, the current system is already drawn and locked for reference — draw your redesigned architecture in the same Low-Level Design area.
- You're welcome to update `docs/DESIGN.md` or add your own files to this repository to capture your design notes as you work.

## Objectives
Please commit an updated DESIGN.md capturing your rationale, decisions and assumptions. Also update the excalidraw diagram to show the changes you propose. You are tasked with the following changes:
- Redesign the shipment lifecycle so transitions are decided in one authoritative place.
- Define clear contracts for commands, transitions, side effects, compensation, and audit queries.
- Preserve one ordered history per shipment, even for hot shipments across regions.
- Ensure retries, failures, and compensation never corrupt a shipment's state.
- Plan a migration that keeps legacy and new code from writing the same shipment concurrently.

## What's in this repository

- `docs/DESIGN.md` — the design document
- The current implementation, organized as a standard Java/Maven project:
  - ShipmentController — `src/main/java/com/lumenpost/shipments/ShipmentController.java`
  - Shipment JPA Entity — `src/main/java/com/lumenpost/shipments/Shipment.java`
  - ShipmentRepository — `src/main/java/com/lumenpost/shipments/ShipmentRepository.java`
  - ShipmentLifecycleService — `src/main/java/com/lumenpost/shipments/ShipmentLifecycleService.java`
  - PaymentCallbackService — `src/main/java/com/lumenpost/shipments/PaymentCallbackService.java`
  - RerouteService — `src/main/java/com/lumenpost/shipments/RerouteService.java`
  - RefundService — `src/main/java/com/lumenpost/shipments/RefundService.java`
  - CapacityClient — `src/main/java/com/lumenpost/shipments/CapacityClient.java`
  - PaymentClient — `src/main/java/com/lumenpost/shipments/PaymentClient.java`
  - CarrierHostClient — `src/main/java/com/lumenpost/shipments/CarrierHostClient.java`
