package com.somepro.infrastructure.persistence.forfeit.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.somepro.infrastructure.persistence.base.BasePO;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * t_pawn_forfeit 表的持久化对象（PO，基础设施层）。只描述表结构，不放业务规则。
 *
 * 表已由 doc/schema/pawn.sql 建好，列名即契约，本类不做任何建表/改表动作。
 * 欠款本息、处置盈亏两笔对账数不入库（表里没有列），翻单时照票面快照与处置时刻重算，
 * 因此本 PO 也没有这两个字段。
 */
@Getter
@Setter
@TableName("t_pawn_forfeit")
public class PawnForfeitPO extends BasePO {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("forfeit_no")
    private String forfeitNo;

    @TableField("ticket_id")
    private Long ticketId;

    @TableField("collateral_id")
    private Long collateralId;

    @TableField("forfeited_at")
    private LocalDateTime forfeitedAt;

    @TableField("dispose_method")
    private String disposeMethod;

    @TableField("recover_amount")
    private BigDecimal recoverAmount;
}
