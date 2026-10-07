package com.somepro.domain.redeem.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import com.somepro.domain.ticket.model.PawnTicket;
import com.somepro.domain.ticket.model.TicketStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 赎当结算聚合根（纯领域对象，不带任何持久化注解）。
 *
 * 一条记录 = 一次赎当。核心不变量：
 * 1. 只有在当（ACTIVE）的当票才赎得了；已赎回 / 已绝当 / 已撤销都是定了案的历史票，不收；
 * 2. 晚于到期日期来赎照收，费用按实际天数算，不额外加罚；
 * 3. 计费按天走：日费率 =（票面上的月利率快照 + 月综合费率快照）÷ 30，
 *    费用 = 当金 × 日费率 × 计费天数；计费天数 = 赎当日期 − 起当日期的自然日数，不足一天按一天算；
 *    应还总额 = 当金 + 费用；金额一律保留两位小数、四舍五入；
 * 4. 利率费率一律照票面上的快照算 —— 那是开票当时抄下来的，赎回时不去读现在的费率配置，
 *    不然老票的账会跟着新配置乱跳；
 * 5. 计费天数、费用、应还总额在办理当下算定并定格落账，日后票再改（利率配置再调）都不回写，
 *    对账才对得平；
 * 6. 同一张票只许赎一回 —— 这是跨聚合（赎当结算 + 当票状态 + 当物状态）的并发约束，
 *    由仓储在写锁内对当票做「仍在当」的条件更新来保证，本聚合只管单笔自身的规则。
 *
 * 赎当单号 redeemNo（SD-2026-0001 样式）由仓储按当年序号生成，全局唯一、一单一号。
 * 赎回办成后，当票从在当转已赎、当物从已典当转已赎回，两处状态由仓储同事务一起翻。
 */
@Getter
@Setter
public class PawnRedeem extends BaseEntity {

    private Long id;

    /** 赎当单号，如 SD-2026-0001；办理时由仓储生成，业务上不可改。 */
    private String redeemNo;

    /** 赎的是哪张当票（t_pawn_ticket.id）。 */
    private Long ticketId;

    /** 赎当办理时刻，由应用层按行里时区补当下时刻，原样落账。 */
    private LocalDateTime redeemedAt;

    /** 计费天数：赎当日期 − 起当日期的自然日数，不足一天按一天算（至少 1 天）。 */
    private Integer usedDays;

    /** 利息与综合费合计（元）= 当金 × 日费率 × 计费天数，两位小数四舍五入。 */
    private BigDecimal feeAmount;

    /** 应还总额（元）= 当金 + 费用，两位小数。 */
    private BigDecimal totalAmount;

    /**
     * 工厂方法：办理一次赎当，当场把该收的本息算清。
     *
     * @param ticket     办理当下从库里读出的当票（状态、当金、起当日期、利率费率快照以它为准）
     * @param redeemedAt 赎当办理时刻（行里时区，由应用层补服务端当下时间，不接受前端指定）
     */
    public static PawnRedeem apply(PawnTicket ticket, LocalDateTime redeemedAt) {
        if (ticket == null || ticket.getId() == null) {
            throw new BizException("必须指定赎的是哪张当票");
        }
        if (ticket.getStatus() != TicketStatus.ACTIVE) {
            throw new BizException("只有在当的当票才能赎当，当前状态："
                    + (ticket.getStatus() == null ? "-" : ticket.getStatus().label()));
        }
        if (redeemedAt == null) {
            throw new BizException("赎当办理时刻缺失，不能赎当");
        }

        // 本息口径只有一套：当金 / 利率费率 / 起当日的校验，连同「晚于到期日照实际天数算、
        // 不加罚」都抽在 RedemptionQuote 里；绝当翻单算到处置日的欠款也走它，
        // 保证柜台真办一次与别处试算分毫不差。
        RedemptionQuote quote = RedemptionQuote.quote(ticket, redeemedAt.toLocalDate());

        PawnRedeem redeem = new PawnRedeem();
        redeem.ticketId = ticket.getId();
        redeem.redeemedAt = redeemedAt;
        redeem.usedDays = quote.usedDays();
        redeem.feeAmount = quote.feeAmount();
        redeem.totalAmount = quote.totalAmount();
        return redeem;
    }
}
