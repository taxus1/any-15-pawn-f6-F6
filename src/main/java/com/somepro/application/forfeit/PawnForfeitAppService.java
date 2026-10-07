package com.somepro.application.forfeit;

import com.somepro.common.exception.BizException;
import com.somepro.domain.forfeit.model.DisposeMethod;
import com.somepro.domain.forfeit.model.ForfeitQuery;
import com.somepro.domain.forfeit.model.PawnForfeit;
import com.somepro.domain.forfeit.repository.PawnForfeitRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.ticket.repository.PawnTicketRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 绝当处置应用服务：编排办理绝当、查看处置单、按当票/处置方式翻处置单三个用例，不写表映射。
 *
 * 出入参用领域对象/基础类型，不认识 PO 与 VO。
 *
 * 办理这条链在这里收口：认票（当票仓储读出最新票面）→ 聚合卡「在当 + 已逾期满三十天」、
 * 按处置方式与回款算出单据与两笔对账数 → 仓储在写锁内把当票与当物状态一起翻、
 * 生成处置单号、同事务落处置单。处置单号唯一与「同票只绝当一回」的并发约束在仓储里；
 * 单笔自身规则（资格、回款非负）在 PawnForfeit 聚合里。
 */
@Service
public class PawnForfeitAppService {

    /** 业务时刻统一按行里所在时区算，避免容器 UTC 下把逾期天数算偏一天。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final PawnForfeitRepository pawnForfeitRepository;
    private final PawnTicketRepository pawnTicketRepository;

    public PawnForfeitAppService(PawnForfeitRepository pawnForfeitRepository,
                                 PawnTicketRepository pawnTicketRepository) {
        this.pawnForfeitRepository = pawnForfeitRepository;
        this.pawnTicketRepository = pawnTicketRepository;
    }

    /**
     * 办理绝当。只有当票还在当、到期日已过今天且逾期满三十天才办得了；
     * 处置方式 AUCTION 拍卖 / CONSIGN 变卖 / WRITE_OFF 核销三选一；回款写实际到手金额，
     * 可为 0、不能为负。同一时点重复递交只成一次（仓储写锁内条件更新兜底）。
     * 处置单号由仓储按 JD-年份-序号 生成；办成后当票转已绝当、当物转已绝当，两处状态一起翻。
     */
    public Mono<PawnForfeit> forfeit(Long ticketId, String disposeMethod, String recoverAmount) {
        if (ticketId == null) {
            return Mono.error(new BizException("必须指定处置的是哪张当票"));
        }
        DisposeMethod method = DisposeMethod.ofCode(blankToNull(disposeMethod));
        BigDecimal recover = parseRecoverAmount(recoverAmount);
        // 处置时刻以服务端行里时区为准，不接受前端指定 —— 逾期天数与欠款都锚定这一天
        LocalDateTime forfeitedAt = LocalDateTime.now(BIZ_ZONE);
        return pawnTicketRepository.findById(ticketId)
                .switchIfEmpty(Mono.error(new BizException("当票不存在")))
                .flatMap(ticket -> pawnForfeitRepository.insert(
                        PawnForfeit.apply(ticket, method, recover, forfeitedAt)));
    }

    /** 查看处置单：id 或 forfeitNo（JD-编号）任一指定。 */
    public Mono<PawnForfeit> detail(Long id, String forfeitNo) {
        if (id != null) {
            return pawnForfeitRepository.findById(id)
                    .switchIfEmpty(Mono.error(new BizException("绝当处置单不存在")));
        }
        if (forfeitNo != null && !forfeitNo.isBlank()) {
            return pawnForfeitRepository.findByForfeitNo(forfeitNo.trim())
                    .switchIfEmpty(Mono.error(new BizException("绝当处置单不存在")));
        }
        return Mono.error(new BizException("请指定要查看的绝当处置单（id 或 forfeitNo）"));
    }

    /**
     * 翻处置单：按当票、按处置方式随意拼，都不填翻整份；一页一页走，
     * 每行带处置单号，并带上到处置日为止的欠款本息与处置盈亏，方便跟拍卖行、寄卖行对号对账。
     */
    public Mono<PageResult<PawnForfeit>> page(int pageNum, int pageSize, Long ticketId, String disposeMethod) {
        if (pageNum < 1 || pageSize < 1) {
            return Mono.error(new BizException("页码与每页条数必须为正整数"));
        }
        ForfeitQuery query = ForfeitQuery.of(ticketId, parseMethodNullable(disposeMethod));
        return pawnForfeitRepository.page(pageNum, pageSize, query);
    }

    /** 翻单条件用：处置方式留空表示不按方式筛；填了就得是合法 code，非法写法照样挡回。 */
    private DisposeMethod parseMethodNullable(String raw) {
        String code = blankToNull(raw);
        return code == null ? null : DisposeMethod.ofCode(code);
    }

    /** 处置回款入参解析：必填，金额可零不可负；空串挡回（没回钱请显式填 0）。 */
    private BigDecimal parseRecoverAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("必须填写处置回款；实际没回钱请填 0，不能留空");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException("处置回款必须是金额数字：" + raw);
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("处置回款不能是负数；实际没回钱请填 0");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
