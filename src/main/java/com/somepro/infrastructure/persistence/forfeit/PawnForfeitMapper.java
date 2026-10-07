package com.somepro.infrastructure.persistence.forfeit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.somepro.infrastructure.persistence.forfeit.po.ForfeitDetailRow;
import com.somepro.infrastructure.persistence.forfeit.po.PawnForfeitPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 绝当处置 Mapper（基础设施层）。
 *
 * BaseMapper 覆盖常规 CRUD（含当票/当物状态翻转的条件 update）；处置单号生成、
 * 以及翻单时连同票面快照一起点回来的连表查询用注解 SQL 写死，不建 XML。
 * 写临界区的命名锁不走 MyBatis（要用独立于事务的连接持锁），
 * 见 PawnForfeitRepositoryImpl#inWriteLock。
 *
 * 阻塞 JDBC API，只能在仓储适配器的 blocking(...) 桥接里调用。
 */
@Mapper
public interface PawnForfeitMapper extends BaseMapper<PawnForfeitPO> {

    /**
     * 取某年全部处置单号（序号在 Java 侧取最大，只选 forfeit_no 一列，数据量小）。
     *
     * 刻意不带 del_flag = 0：单号一经分配永久占用 —— 哪怕那条处置单后来被删除，
     * 它的号也不能再发给新单。不能直接 ORDER BY 字符串 DESC LIMIT 1：
     * 字符串排序下 JD-2026-9999 会排在 JD-2026-10000 前面。
     */
    @Select("SELECT forfeit_no FROM t_pawn_forfeit WHERE forfeit_no LIKE #{prefix}")
    List<String> findForfeitNosByPrefix(@Param("prefix") String prefix);

    /**
     * 处置单（主）LEFT JOIN 当票，按处置单 id 点一条；票缺失时票面列为 null。
     */
    @Select("""
            SELECT f.id               AS id,
                   f.forfeit_no       AS forfeit_no,
                   f.ticket_id        AS ticket_id,
                   f.collateral_id    AS collateral_id,
                   f.forfeited_at     AS forfeited_at,
                   f.dispose_method   AS dispose_method,
                   f.recover_amount   AS recover_amount,
                   f.create_time      AS create_time,
                   t.start_date       AS start_date,
                   t.due_date         AS due_date,
                   t.pawn_amount      AS pawn_amount,
                   t.monthly_rate     AS monthly_rate,
                   t.service_rate     AS service_rate
            FROM t_pawn_forfeit f
            LEFT JOIN t_pawn_ticket t ON t.id = f.ticket_id
            WHERE f.id = #{id} AND f.del_flag = 0
            """)
    ForfeitDetailRow selectDetailById(@Param("id") Long id);

    /**
     * 处置单（主）LEFT JOIN 当票，按处置单号点一条；票缺失时票面列为 null。
     */
    @Select("""
            SELECT f.id               AS id,
                   f.forfeit_no       AS forfeit_no,
                   f.ticket_id        AS ticket_id,
                   f.collateral_id    AS collateral_id,
                   f.forfeited_at     AS forfeited_at,
                   f.dispose_method   AS dispose_method,
                   f.recover_amount   AS recover_amount,
                   f.create_time      AS create_time,
                   t.start_date       AS start_date,
                   t.due_date         AS due_date,
                   t.pawn_amount      AS pawn_amount,
                   t.monthly_rate     AS monthly_rate,
                   t.service_rate     AS service_rate
            FROM t_pawn_forfeit f
            LEFT JOIN t_pawn_ticket t ON t.id = f.ticket_id
            WHERE f.forfeit_no = #{forfeitNo} AND f.del_flag = 0
            """)
    ForfeitDetailRow selectDetailByNo(@Param("forfeitNo") String forfeitNo);

    /**
     * 翻处置单：当票、处置方式随意拼（script 动态条件），稳定按处置单 id 升序。
     * 分页由 PageHelper 在调用前 {@code PageHelper.startPage} 织入（含 count）。
     * 主表带 del_flag = 0；当票走 LEFT JOIN（只点算欠款要的快照列，不要求票一定在）。
     */
    @Select("""
            <script>
            SELECT f.id               AS id,
                   f.forfeit_no       AS forfeit_no,
                   f.ticket_id        AS ticket_id,
                   f.collateral_id    AS collateral_id,
                   f.forfeited_at     AS forfeited_at,
                   f.dispose_method   AS dispose_method,
                   f.recover_amount   AS recover_amount,
                   f.create_time      AS create_time,
                   t.start_date       AS start_date,
                   t.due_date         AS due_date,
                   t.pawn_amount      AS pawn_amount,
                   t.monthly_rate     AS monthly_rate,
                   t.service_rate     AS service_rate
            FROM t_pawn_forfeit f
            LEFT JOIN t_pawn_ticket t ON t.id = f.ticket_id
            WHERE f.del_flag = 0
            <if test="ticketId != null">
                AND f.ticket_id = #{ticketId}
            </if>
            <if test="disposeMethod != null and disposeMethod != ''">
                AND f.dispose_method = #{disposeMethod}
            </if>
            ORDER BY f.id ASC
            </script>
            """)
    List<ForfeitDetailRow> selectDetailPage(@Param("ticketId") Long ticketId,
                                            @Param("disposeMethod") String disposeMethod);
}
