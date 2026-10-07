package com.somepro.domain.forfeit.repository;

import com.somepro.domain.forfeit.model.ForfeitQuery;
import com.somepro.domain.forfeit.model.PawnForfeit;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * 绝当处置仓储端口（领域层定义，基础设施层实现）。
 *
 * {@link #insert} 的实现在写临界区（MySQL 命名锁 + 同一事务）里保证三件事：
 * 1. 处置单号全局唯一（JD-年份-序号，一单一号）：锁内取当年最大序号 +1，唯一索引兜底，
 *    撞号整段重试，不把底层冲突甩给柜台；
 * 2. 同一张票只绝当一回：锁内对当票做条件更新（id + 状态仍是 ACTIVE 才翻成 FORFEITED）——
 *    柜台手快把同一笔绝当重复递进来，后到那笔条件已不成立，挡回，
 *    处置单不会平白多出一笔；票在办理瞬间被撤销/赎回（状态离开 ACTIVE）同样挡回；
 * 3. 当票「在当 → 已绝当」、当物「已典当 → 已绝当」与处置单写入同一事务，
 *    两处状态一起翻，要么一起成、要么一起回滚，不会只翻一处。
 */
public interface PawnForfeitRepository {

    /**
     * 办理落库：分配雪花 id、生成全局唯一处置单号（JD-年份-序号），
     * 在同一事务内把当票从在当翻成已绝当、把票押的当物从已典当翻成已绝当，再写处置单。
     * 当票或当物状态已变化（重复递交/并发办理）时抛业务异常，几处都不落。
     */
    Mono<PawnForfeit> insert(PawnForfeit forfeit);

    Mono<PawnForfeit> findById(Long id);

    Mono<PawnForfeit> findByForfeitNo(String forfeitNo);

    /**
     * 按条件翻处置单（当票、处置方式随意拼）；逻辑删除的不出现，稳定按 id 升序分页，
     * 每行带处置单号，并回填到处置日为止的欠款本息与处置盈亏两笔对账数。
     */
    Mono<PageResult<PawnForfeit>> page(int pageNum, int pageSize, ForfeitQuery query);
}
