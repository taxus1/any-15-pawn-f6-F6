package com.somepro.domain.forfeit.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.redeem.model.RedemptionQuote;
import com.somepro.domain.shared.model.BaseEntity;
import com.somepro.domain.ticket.model.PawnTicket;
import com.somepro.domain.ticket.model.TicketStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 绝当处置聚合根（纯领域对象，不带任何持久化注解）。
 *
 * 一条记录 = 一次绝当。核心不变量：
 * 1. 只有在当（ACTIVE）的当票才轮得到绝当；已赎回 / 已绝当 / 已撤销都是定了案的历史票，不收；
 * 2. 到期日子必须已经过了今天，并且逾期满 {@value #OVERDUE_REQUIRED_DAYS} 天才轮得到绝当 ——
 *    还没到期的、刚到期（到期日当天）的、逾期没满三十天的都不给办；
 * 3. 处置回款写实际到手的金额：可以是 0（拍不动 / 卖不掉核销），但不能是负数；
 * 4. 处置方式三选一：AUCTION 拍卖 / CONSIGN 变卖 / WRITE_OFF 核销；
 * 5. 同一张票只许绝当一回 —— 这是跨聚合（绝当处置 + 当票状态 + 当物状态）的并发约束，
 *    由仓储在写锁内对当票做「仍在当」的条件更新来保证，本聚合只管单笔自身的规则。
 *
 * 绝当处置单号 forfeitNo（JD-2026-0001 样式）由仓储按当年序号生成，全局唯一、一单一号。
 * 绝当办成后，当票从在当转已绝当、当物从已典当转已绝当，两处状态由仓储同事务一起翻。
 *
 * 绝当到底是为这笔账：光有处置回款，看不出行里是赚是亏。所以本聚合除落库列外，
 * 还挂两笔【算出来、不入库】的数（翻单时由仓储照票面重算回填，不新增表字段）：
 * - owedAmount 到处置日为止这张票还欠行里多少本息 —— 真在那天客户来赎柜台该收的钱，
 *   口径直接走赎当那套（{@link RedemptionQuote}），逾期也照实际天数算、不加罚，本处不另立；
 * - profitLossAmount 处置回款 − 欠款：回款不够记成亏（负）、多出来记成盈（正）、打平为 0。
 */
@Getter
@Setter
public class PawnForfeit extends BaseEntity {

    /** 绝当门槛：到期日过后得逾期满这么多天才轮得到绝当（到期日当天算第 0 天，还没逾期）。 */
    public static final int OVERDUE_REQUIRED_DAYS = 30;

    private Long id;

    /** 绝当处置单号，如 JD-2026-0001；办理时由仓储生成，业务上不可改。 */
    private String forfeitNo;

    /** 处置的是哪张当票（t_pawn_ticket.id）。 */
    private Long ticketId;

    /** 票上押的那件当物（t_collateral.id），办理当下从票面定格抄录。 */
    private Long collateralId;

    /** 绝当处置时刻，由应用层按行里时区补当下时刻，原样落账。 */
    private LocalDateTime forfeitedAt;

    /** 处置方式：AUCTION 拍卖 / CONSIGN 变卖 / WRITE_OFF 核销。 */
    private DisposeMethod disposeMethod;

    /** 处置回款（元）：实际到手金额，可以是 0，不能是负数。 */
    private BigDecimal recoverAmount;

    // ---- 以下两笔是对账视图，不入库：办理当下算一遍、翻单时照票面重算，由仓储回填 ----

    /** 到处置日为止票欠行里的本息（= 那天来赎柜台该收的总额），口径照赎当那套。 */
    private BigDecimal owedAmount;

    /** 处置回款 − 欠款：正为盈、负为亏、0 为平。 */
    private BigDecimal profitLossAmount;

    /**
     * 工厂方法：办理一次绝当。办理时刻即处置时刻，逾期天数与欠款都锚定这一天。
     *
     * @param ticket         办理当下从库里读出的当票（状态、到期日、当金、利率费率快照、当物 id 以它为准）
     * @param disposeMethod  处置方式（拍卖 / 变卖 / 核销）
     * @param recoverAmount  处置回款，实际到手金额；可以为 0，不能为负
     * @param forfeitedAt    绝当处置时刻（行里时区，由应用层补服务端当下时间，不接受前端指定）
     */
    public static PawnForfeit apply(PawnTicket ticket, DisposeMethod disposeMethod,
                                    BigDecimal recoverAmount, LocalDateTime forfeitedAt) {
        if (ticket == null || ticket.getId() == null) {
            throw new BizException("必须指定处置的是哪张当票");
        }
        if (ticket.getStatus() != TicketStatus.ACTIVE) {
            throw new BizException("只有在当的当票才能办绝当，当前状态："
                    + (ticket.getStatus() == null ? "-" : ticket.getStatus().label()));
        }
        LocalDate dueDate = ticket.getDueDate();
        if (dueDate == null) {
            throw new BizException("当票到期日期缺失，不能办绝当");
        }
        if (forfeitedAt == null) {
            throw new BizException("绝当处置时刻缺失，不能办绝当");
        }
        if (disposeMethod == null) {
            throw new BizException("必须指定处置方式：AUCTION 拍卖 / CONSIGN 变卖 / WRITE_OFF 核销");
        }
        if (recoverAmount == null) {
            throw new BizException("必须填写处置回款；实际没回钱请填 0，不能留空");
        }
        if (recoverAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("处置回款不能是负数；实际没回钱请填 0");
        }

        // 逾期天数 = 今天（处置日）− 到期日的自然日数。到期日当天为 0（刚到期、不算逾期）。
        LocalDate disposeDate = forfeitedAt.toLocalDate();
        long overdueDays = ChronoUnit.DAYS.between(dueDate, disposeDate);
        if (overdueDays <= 0) {
            // 还没到期（负）、到期日当天（0）都不给办
            throw new BizException("当票尚未逾期（到期日期：" + dueDate + "），还轮不到绝当");
        }
        if (overdueDays < OVERDUE_REQUIRED_DAYS) {
            throw new BizException("逾期未满三十天（当前逾期 " + overdueDays + " 天），还不能办绝当");
        }

        // 欠款照赎当那套口径，算到处置日为止：那天客户真来赎，柜台该收的就是这一笔。
        BigDecimal owed = RedemptionQuote.quote(ticket, disposeDate).totalAmount();
        // 差额：回款 − 欠款，回款不够为亏（负）、多出来为盈（正）。
        BigDecimal profitLoss = recoverAmount.subtract(owed).setScale(2, RoundingMode.HALF_UP);

        PawnForfeit forfeit = new PawnForfeit();
        forfeit.ticketId = ticket.getId();
        forfeit.collateralId = ticket.getCollateralId();
        forfeit.forfeitedAt = forfeitedAt;
        forfeit.disposeMethod = disposeMethod;
        forfeit.recoverAmount = recoverAmount.setScale(2, RoundingMode.HALF_UP);
        forfeit.owedAmount = owed;
        forfeit.profitLossAmount = profitLoss;
        return forfeit;
    }

    /**
     * 翻单时回填两笔对账数：仓储照库里票面与处置日重算欠款、差额。
     * 不重验办理资格（票此刻已是已绝当），只算数；票面档案缺失等异常情况下两笔留 null，不吞单。
     */
    public void fillSettlement(PawnTicket ticket) {
        if (ticket == null || this.forfeitedAt == null) {
            return;
        }
        BigDecimal owed = RedemptionQuote.quote(ticket, this.forfeitedAt.toLocalDate()).totalAmount();
        this.owedAmount = owed;
        this.profitLossAmount = this.recoverAmount.subtract(owed).setScale(2, RoundingMode.HALF_UP);
    }
}
