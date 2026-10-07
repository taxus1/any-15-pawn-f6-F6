package com.somepro.interfaces.rest.forfeit.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 绝当处置单对外对象（不可变 record）：办理/查看/翻单共用。
 *
 * 每行都带 forfeitNo（JD-年份-序号），方便柜台跟拍卖行、寄卖行的回单对号；
 * recoverAmount 是实际到手的处置回款。
 *
 * 两笔对账数摆在一起，盈亏一眼看清：
 * - owedAmount 到处置日为止这张票还欠行里的本息（= 那天客户真来赎柜台该收的钱，口径照赎当那套）；
 * - profitLossAmount 处置回款 − 欠款：回款不够为亏（负数）、多出来为盈（正数）、恰好打平为 0。
 * 刻意不暴露 delFlag / createBy / updateBy 等内部字段。
 */
public record PawnForfeitVO(Long id,
                            String forfeitNo,
                            Long ticketId,
                            Long collateralId,
                            LocalDateTime forfeitedAt,
                            String disposeMethod,
                            BigDecimal recoverAmount,
                            BigDecimal owedAmount,
                            BigDecimal profitLossAmount,
                            LocalDateTime createTime) implements Serializable {
}
