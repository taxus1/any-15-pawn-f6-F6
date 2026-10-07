package com.somepro.domain.redeem.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.ticket.model.PawnTicket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 赎当口径的应还试算（纯领域值对象，不可变、不落库）。
 *
 * 抽出来的原因：行里「真要是那天客户来赎，柜台该收多少钱」只有一套口径，
 * 赎当办理（{@link PawnRedeem#apply}）与绝当翻单（算到处置日为止这张票还欠行里多少本息）
 * 都必须走它，不许哪一头另立一套，不然柜台跟拍卖行、寄卖行对账时两笔数对不上。
 *
 * 口径（与赎当办理逐字一致）：
 * 1. 日费率 =（票面上的月利率快照 + 月综合费率快照）÷ 30；
 * 2. 计费天数 = 结算日期 − 起当日期的自然日数，不足一天按一天算（至少 1 天），
 *    晚于到期日期也照实际天数算，不额外加罚；
 * 3. 利息与综合费 = 当金 × 日费率 × 计费天数；
 * 4. 应还总额 = 当金 + 费用；金额一律保留两位小数、四舍五入；
 * 5. 利率费率一律照票面上的快照算，不读办理当天的费率配置。
 *
 * 本对象只算数、不卡票状态（在当 / 已绝当的票都可能要这笔数）；
 * 「什么状态、什么日子办得了什么业务」由各自的办理聚合去卡。
 *
 * @param usedDays    计费天数（至少 1）
 * @param feeAmount   利息与综合费合计（元，两位小数）
 * @param totalAmount 应还总额 = 当金 + 费用（元，两位小数）
 */
public record RedemptionQuote(Integer usedDays,
                              BigDecimal feeAmount,
                              BigDecimal totalAmount) {

    /** 日费率分母：月利率、月综合费率都是「每月」口径，折成每天除以 30。 */
    private static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(30);

    /**
     * 照票面快照，算「结算日当天来赎柜台该收的本息」。
     *
     * @param ticket       按库里最新票面重建的当票（当金、起当日期、利率费率快照以它为准）
     * @param settleDate   结算日期（如赎当日 / 绝当处置日；按行里时区取的服务端日期）
     */
    public static RedemptionQuote quote(PawnTicket ticket, LocalDate settleDate) {
        if (ticket == null || ticket.getId() == null) {
            throw new BizException("必须指定对应的是哪张当票");
        }
        if (ticket.getPawnAmount() == null || ticket.getPawnAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("当票当金异常，无法结算本息");
        }
        if (ticket.getMonthlyRate() == null || ticket.getServiceRate() == null) {
            throw new BizException("当票利率费率快照缺失，无法结算本息");
        }
        LocalDate startDate = ticket.getStartDate();
        if (startDate == null) {
            throw new BizException("当票起当日期缺失，无法结算本息");
        }
        if (settleDate == null) {
            throw new BizException("结算日期缺失，无法结算本息");
        }

        // 计费天数：结算日期 − 起当日期的自然日数，不足一天按一天算。
        // 晚于到期日期也照实际天数算，实际多少天算多少天，不额外加罚。
        int usedDays = (int) Math.max(1L, ChronoUnit.DAYS.between(startDate, settleDate));

        // 日费率 =（月利率快照 + 月综合费率快照）÷ 30；费用 = 当金 × 日费率 × 计费天数。
        // 一律照票面上的快照算，不读现在的费率配置；金额保留两位小数四舍五入。
        BigDecimal dailyRate = ticket.getMonthlyRate().add(ticket.getServiceRate())
                .divide(DAYS_PER_MONTH, 10, RoundingMode.HALF_UP);
        BigDecimal fee = ticket.getPawnAmount()
                .multiply(dailyRate)
                .multiply(BigDecimal.valueOf(usedDays))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = ticket.getPawnAmount().add(fee).setScale(2, RoundingMode.HALF_UP);
        return new RedemptionQuote(usedDays, fee, total);
    }
}
