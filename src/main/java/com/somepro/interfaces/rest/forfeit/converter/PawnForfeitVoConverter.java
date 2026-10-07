package com.somepro.interfaces.rest.forfeit.converter;

import com.somepro.domain.forfeit.model.PawnForfeit;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.forfeit.vo.PawnForfeitVO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 绝当领域对象 → VO 转换器（用户接口层）。Controller 不直接把领域对象塞进 Result。
 */
public final class PawnForfeitVoConverter {

    private PawnForfeitVoConverter() {
    }

    public static PawnForfeitVO toVo(PawnForfeit domain) {
        return new PawnForfeitVO(
                domain.getId(),
                domain.getForfeitNo(),
                domain.getTicketId(),
                domain.getCollateralId(),
                domain.getForfeitedAt(),
                domain.getDisposeMethod() == null ? null : domain.getDisposeMethod().code(),
                domain.getRecoverAmount(),
                domain.getOwedAmount(),
                domain.getProfitLossAmount(),
                domain.getCreateTime());
    }

    public static PageVO<PawnForfeitVO> toPageVo(PageResult<PawnForfeit> page) {
        List<PawnForfeitVO> content = page.content().stream()
                .map(PawnForfeitVoConverter::toVo)
                .collect(Collectors.toList());
        return new PageVO<>(content, page.total(), page.pageNum(), page.pageSize(), page.totalPages());
    }
}
