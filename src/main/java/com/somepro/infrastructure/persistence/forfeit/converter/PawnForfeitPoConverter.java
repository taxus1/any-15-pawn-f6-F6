package com.somepro.infrastructure.persistence.forfeit.converter;

import com.somepro.domain.forfeit.model.DisposeMethod;
import com.somepro.domain.forfeit.model.PawnForfeit;
import com.somepro.infrastructure.persistence.forfeit.po.PawnForfeitPO;

/**
 * PawnForfeitPO（表）↔ PawnForfeit（领域）转换器（基础设施层），PO 不外泄。
 * dispose_method 列存枚举名，这里与领域枚举互转；
 * 欠款本息 / 处置盈亏是算出来的对账视图、不入库，故不在本转换器里搬运。
 */
public final class PawnForfeitPoConverter {

    private PawnForfeitPoConverter() {
    }

    public static PawnForfeitPO toPo(PawnForfeit domain) {
        PawnForfeitPO po = new PawnForfeitPO();
        po.setId(domain.getId());
        po.setForfeitNo(domain.getForfeitNo());
        po.setTicketId(domain.getTicketId());
        po.setCollateralId(domain.getCollateralId());
        po.setForfeitedAt(domain.getForfeitedAt());
        po.setDisposeMethod(domain.getDisposeMethod() == null ? null : domain.getDisposeMethod().code());
        po.setRecoverAmount(domain.getRecoverAmount());
        po.setDelFlag(domain.getDelFlag());
        po.setCreateBy(domain.getCreateBy());
        po.setCreateTime(domain.getCreateTime());
        po.setUpdateBy(domain.getUpdateBy());
        po.setUpdateTime(domain.getUpdateTime());
        return po;
    }

    public static PawnForfeit toDomain(PawnForfeitPO po) {
        PawnForfeit domain = new PawnForfeit();
        fill(domain, po.getId(), po.getForfeitNo(), po.getTicketId(), po.getCollateralId(),
                po.getForfeitedAt(), po.getDisposeMethod(), po.getRecoverAmount(),
                po.getCreateTime());
        domain.setDelFlag(po.getDelFlag());
        domain.setCreateBy(po.getCreateBy());
        domain.setUpdateBy(po.getUpdateBy());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }

    /**
     * 投影行（处置单 + 票面快照）→ 领域对象：基础列照映射，票面列交给上层补算欠款/盈亏，
     * 这里只搬处置单自身字段与 create_time。
     */
    public static PawnForfeit toDomain(com.somepro.infrastructure.persistence.forfeit.po.ForfeitDetailRow row) {
        PawnForfeit domain = new PawnForfeit();
        fill(domain, row.getId(), row.getForfeitNo(), row.getTicketId(), row.getCollateralId(),
                row.getForfeitedAt(), row.getDisposeMethod(), row.getRecoverAmount(),
                row.getCreateTime());
        return domain;
    }

    private static void fill(PawnForfeit domain, Long id, String forfeitNo, Long ticketId,
                             Long collateralId, java.time.LocalDateTime forfeitedAt,
                             String disposeMethod, java.math.BigDecimal recoverAmount,
                             java.time.LocalDateTime createTime) {
        domain.setId(id);
        domain.setForfeitNo(forfeitNo);
        domain.setTicketId(ticketId);
        domain.setCollateralId(collateralId);
        domain.setForfeitedAt(forfeitedAt);
        domain.setDisposeMethod(disposeMethod == null ? null : DisposeMethod.valueOf(disposeMethod));
        domain.setRecoverAmount(recoverAmount);
        domain.setCreateTime(createTime);
    }
}
