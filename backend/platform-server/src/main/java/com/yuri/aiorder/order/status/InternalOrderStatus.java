package com.yuri.aiorder.order.status;

public enum InternalOrderStatus {
    DRAFT(null),
    PENDING_CS_REVIEW(ExternalOrderStatus.PENDING_REVIEW),
    CS_REJECTED(ExternalOrderStatus.PENDING_REVIEW),
    PENDING_PRODUCTION_REVIEW(ExternalOrderStatus.PENDING_REVIEW),
    PRODUCTION_REJECTED(ExternalOrderStatus.PENDING_REVIEW),
    PROCESS_INSTANCE_CREATED(ExternalOrderStatus.PRODUCING),
    ASSIGNED(ExternalOrderStatus.PRODUCING),
    IN_DESIGN(ExternalOrderStatus.DESIGNING),
    IN_PRODUCTION(ExternalOrderStatus.PRODUCING),
    IN_QC(ExternalOrderStatus.QC),
    QC_PASSED(ExternalOrderStatus.PENDING_SHIP),
    SHIPPED(ExternalOrderStatus.SHIPPED),
    COMPLETED(ExternalOrderStatus.COMPLETED);

    private final ExternalOrderStatus externalStatus;

    InternalOrderStatus(ExternalOrderStatus externalStatus) {
        this.externalStatus = externalStatus;
    }

    public ExternalOrderStatus externalStatus() {
        if (externalStatus == null) {
            throw new IllegalStateException("DRAFT has no doctor-facing external status");
        }
        return externalStatus;
    }
}
