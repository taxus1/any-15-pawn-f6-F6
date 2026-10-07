package com.somepro.infrastructure.persistence.forfeit.po;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 绝当处置单连表查询投影行（基础设施层）。
 *
 * 不是任何一张表的 PO、没有 @TableName —— 它把处置单（主）LEFT JOIN 当票，
 * 把「到处置日为止这张票还欠多少本息」要用到的票面快照列一起带回来，由 MyBatis 按列名填充。
 * 只在基础设施层内使用，不外泄到领域 / 接口层。
 *
 * 为什么 LEFT JOIN 当票：翻单基准是处置单，票面档案异常（理论上不该发生）不应把处置单吞掉；
 * 票面列缺失时欠款 / 盈亏两笔算不出来，留 null，处置单本身照翻。
 */
@Getter
@Setter
public class ForfeitDetailRow {

    // ---- 处置单（主表）----
    private Long id;
    private String forfeitNo;
    private Long ticketId;
    private Long collateralId;
    private LocalDateTime forfeitedAt;
    private String disposeMethod;
    private BigDecimal recoverAmount;
    private LocalDateTime createTime;

    // ---- 当票（LEFT JOIN）：算欠款要的票面快照，缺票时整组为 null ----
    private LocalDate startDate;
    private LocalDate dueDate;
    private BigDecimal pawnAmount;
    private BigDecimal monthlyRate;
    private BigDecimal serviceRate;
}
