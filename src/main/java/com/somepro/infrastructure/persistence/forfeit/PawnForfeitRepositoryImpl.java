package com.somepro.infrastructure.persistence.forfeit;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.collateral.model.CollateralStatus;
import com.somepro.domain.forfeit.model.ForfeitQuery;
import com.somepro.domain.forfeit.model.PawnForfeit;
import com.somepro.domain.forfeit.repository.PawnForfeitRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.ticket.model.PawnTicket;
import com.somepro.domain.ticket.model.TicketStatus;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.forfeit.converter.PawnForfeitPoConverter;
import com.somepro.infrastructure.persistence.forfeit.po.ForfeitDetailRow;
import com.somepro.infrastructure.persistence.forfeit.po.PawnForfeitPO;
import com.somepro.infrastructure.persistence.ticket.PawnTicketMapper;
import com.somepro.infrastructure.persistence.ticket.TicketCollateralMapper;
import com.somepro.infrastructure.persistence.ticket.po.PawnTicketPO;
import com.somepro.infrastructure.persistence.ticket.po.TicketCollateralPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 绝当处置仓储适配器（基础设施层）：MyBatis-Plus 阻塞 JDBC 经 blocking(...) 桥接进响应式链路。
 *
 * 本类三处关键业务语义（与赎当仓储同一套，只是翻向已绝当）：
 *
 * 1. 同票只绝当一回（含并发重复递交）
 *    办理先抢 MySQL 命名锁 GET_LOCK('pawn_forfeit:write')（全实例互斥），锁内事务里先对当票做
 *    条件更新：id 命中、状态仍是 ACTIVE 才翻成 FORFEITED。柜台手快重复递交同一笔，第二笔拿到
 *    锁时票面状态已被第一笔翻走，条件不成立、更新 0 行，整段回滚 —— 处置单只落一条，
 *    票和物也只翻一次。票在办理瞬间被撤销/赎回（状态离开 ACTIVE）同样挡回。
 *    （在当 + 逾期满三十天的资格在领域聚合办理时已卡过；状态条件更新是并发兜底。）
 *
 * 2. 处置单号生成 JD-yyyy-NNNN
 *    同一把写锁内：取当年处置单号的最大整数序号 +1（序号在 Java 侧解析，
 *    避免字符串排序把 9999 排在 10000 前），锁内算号天然不撞；
 *    取号刻意包含已删除的处置单：单号一经分配永久占用。
 *    uk_forfeit_no 唯一索引是最后防线，极端瞬态冲突整段重试，不甩底层错给柜台。
 *
 * 3. 票物状态联动与处置单写入同一事务
 *    当票「在当 → 已绝当」、当物「已典当 → 已绝当」与处置单写入在同一事务里落库，
 *    两处状态一起翻，要么一起成、要么一起回滚，不会出现「票已绝当、物还押着」的裂账。
 *    当物只翻当前是「已典当」的，别踩了别的流程置的状态。
 *
 * 翻单（detail/page）走处置单 LEFT JOIN 当票的连表查询，把票面快照一起点回来，
 * 照处置时刻重算「到处置日的欠款本息」与「处置盈亏」两笔对账数（不入库，不新增表字段）。
 *
 * 锁的连接与时序同赎当/当票模块：用一条【独立于事务的原始连接】在事务开启前 GET_LOCK、
 * 在事务【提交之后】才 RELEASE_LOCK，避免「锁已放、事务未提交」导致后到者漏看刚翻走的状态。
 */
@Repository
public class PawnForfeitRepositoryImpl implements PawnForfeitRepository {

    /** 业务日期统一按行里所在时区算，避免容器 UTC 下单号跨年。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    /** 绝当办理临界区命名锁（MySQL 全实例同名互斥）。 */
    private static final String WRITE_LOCK = "pawn_forfeit:write";
    private static final int LOCK_WAIT_SECONDS = 10;
    private static final int MAX_RETRY = 5;

    private final PawnForfeitMapper pawnForfeitMapper;
    private final PawnTicketMapper pawnTicketMapper;
    private final TicketCollateralMapper ticketCollateralMapper;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;

    public PawnForfeitRepositoryImpl(PawnForfeitMapper pawnForfeitMapper,
                                     PawnTicketMapper pawnTicketMapper,
                                     TicketCollateralMapper ticketCollateralMapper,
                                     PlatformTransactionManager transactionManager,
                                     DataSource dataSource) {
        this.pawnForfeitMapper = pawnForfeitMapper;
        this.pawnTicketMapper = pawnTicketMapper;
        this.ticketCollateralMapper = ticketCollateralMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.dataSource = dataSource;
    }

    @Override
    public Mono<PawnForfeit> insert(PawnForfeit forfeit) {
        return blocking(() -> {
            // 每轮重试用独立连接重新抢锁；兜住单号撞号 / 锁等待超时等瞬态冲突
            for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
                try {
                    PawnForfeit saved = inWriteLock(() -> transactionTemplate.execute(status -> {
                        // 同票只绝当一回：条件更新「在当 → 已绝当」才作数。
                        // 重复递交的第二笔状态已不在当，更新 0 行，整段回滚不落记录。
                        PawnTicketPO ticketUpdate = new PawnTicketPO();
                        ticketUpdate.setStatus(TicketStatus.FORFEITED.code());
                        int rows = pawnTicketMapper.update(ticketUpdate,
                                Wrappers.<PawnTicketPO>lambdaUpdate()
                                        .eq(PawnTicketPO::getId, forfeit.getTicketId())
                                        .eq(PawnTicketPO::getStatus, TicketStatus.ACTIVE.code()));
                        if (rows == 0) {
                            throw new BizException("当票状态已变化，本次绝当未生效；请刷新后按最新票面办理");
                        }
                        // 票物联动：票绝当了，押的当物跟着从已典当转已绝当，同一事务一起翻。
                        // 当物 id 以库里的票面为准（同事务内读，刚翻过的状态本连接可见）。
                        PawnTicketPO ticket = pawnTicketMapper.selectById(forfeit.getTicketId());
                        if (ticket == null) {
                            throw new BizException("当票不存在");
                        }
                        markCollateralForfeited(ticket.getCollateralId());

                        PawnForfeitPO po = PawnForfeitPoConverter.toPo(forfeit);
                        po.setId(IdUtil.getSnowflakeNextId());
                        po.setForfeitNo(nextForfeitNo());
                        pawnForfeitMapper.insert(po);

                        // 回到入参聚合（已含办理时算好的欠款/盈亏），只回填落库分配的 id、单号与审计时刻
                        forfeit.setId(po.getId());
                        forfeit.setForfeitNo(po.getForfeitNo());
                        forfeit.setCreateTime(po.getCreateTime());
                        return forfeit;
                    }));
                    return saved;
                } catch (DuplicateKeyException | TransientDataAccessException e) {
                    // uk_forfeit_no 是最后防线，锁内正常不会撞；撞了整段重新取号重试
                    if (attempt == MAX_RETRY - 1) {
                        throw new BizException("系统繁忙，请稍后重试");
                    }
                    try {
                        Thread.sleep(10L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BizException("系统繁忙，请稍后重试");
                    }
                }
            }
            throw new BizException("系统繁忙，请稍后重试");
        });
    }

    @Override
    public Mono<PawnForfeit> findById(Long id) {
        return blocking(() -> toDomainWithSettlement(pawnForfeitMapper.selectDetailById(id)));
    }

    @Override
    public Mono<PawnForfeit> findByForfeitNo(String forfeitNo) {
        return blocking(() -> toDomainWithSettlement(pawnForfeitMapper.selectDetailByNo(forfeitNo)));
    }

    @Override
    public Mono<PageResult<PawnForfeit>> page(int pageNum, int pageSize, ForfeitQuery query) {
        return this.<PageResult<PawnForfeit>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                String methodCode = query.disposeMethod() == null ? null : query.disposeMethod().code();
                List<ForfeitDetailRow> rows = pawnForfeitMapper.selectDetailPage(query.ticketId(), methodCode);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<PawnForfeit> content = rows.stream()
                        .map(this::toDomainWithSettlement)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                // PageHelper 靠 ThreadLocal 传分页参数，必须清，避免污染线程池下一次调用
                PageHelper.clearPage();
            }
        });
    }

    /**
     * 连表投影行 → 领域对象，并照票面快照与处置时刻回填欠款本息 / 处置盈亏两笔对账数。
     * 票缺失（LEFT JOIN 未命中，理论上不该发生）时两笔算不出来，留 null，处置单照翻。
     */
    private PawnForfeit toDomainWithSettlement(ForfeitDetailRow row) {
        if (row == null) {
            return null;
        }
        PawnForfeit domain = PawnForfeitPoConverter.toDomain(row);
        if (row.getStartDate() != null && row.getDueDate() != null
                && row.getPawnAmount() != null
                && row.getMonthlyRate() != null && row.getServiceRate() != null) {
            domain.fillSettlement(toTicket(row));
        }
        return domain;
    }

    /** 投影行里的票面快照列 → 当票领域对象，只填算欠款用得到的列（id / 当金 / 起当日 / 利率费率）。 */
    private static PawnTicket toTicket(ForfeitDetailRow row) {
        PawnTicket ticket = new PawnTicket();
        ticket.setId(row.getTicketId());
        ticket.setCollateralId(row.getCollateralId());
        ticket.setPawnAmount(row.getPawnAmount());
        ticket.setMonthlyRate(row.getMonthlyRate());
        ticket.setServiceRate(row.getServiceRate());
        ticket.setStartDate(row.getStartDate());
        ticket.setDueDate(row.getDueDate());
        return ticket;
    }

    /**
     * 生成 JD-年份-序号：序号是当年已有处置单号（含已删除）最大整数 +1，至少 4 位、超出自然进位。
     * 只在写锁（{@link #inWriteLock}）内调用，锁内串行所以不会撞号；
     * forfeit_no 唯一索引是最后防线，极端瞬态冲突由外层整段重试兜底。
     */
    private String nextForfeitNo() {
        int year = LocalDate.now(BIZ_ZONE).getYear();
        String prefix = "JD-" + year + "-";
        long maxSeq = 0L;
        for (String no : pawnForfeitMapper.findForfeitNosByPrefix(prefix + "%")) {
            if (no == null || !no.startsWith(prefix)) {
                continue;
            }
            String tail = no.substring(prefix.length());
            if (tail.chars().allMatch(Character::isDigit)) {
                maxSeq = Math.max(maxSeq, Long.parseLong(tail));
            }
        }
        return prefix + String.format("%04d", maxSeq + 1);
    }

    /**
     * 当物状态联动：已典当 → 已绝当。只翻当前状态是「已典当」的行，别踩了别的流程置的状态。
     * 更新 0 行即状态已被人动过，抛业务异常让整段事务回滚，票、物、处置单几处都不落。
     */
    private void markCollateralForfeited(Long collateralId) {
        TicketCollateralPO update = new TicketCollateralPO();
        update.setStatus(CollateralStatus.FORFEITED.code());
        int rows = ticketCollateralMapper.update(update,
                Wrappers.<TicketCollateralPO>lambdaUpdate()
                        .eq(TicketCollateralPO::getId, collateralId)
                        .eq(TicketCollateralPO::getStatus, CollateralStatus.PAWNED.code()));
        if (rows == 0) {
            throw new BizException("当物状态已变化，本次绝当未生效；请刷新后按最新状态办理");
        }
    }

    /**
     * 在全局命名锁保护下执行一段【含事务】的写入：锁由一条独立原始连接持有，
     * 在事务开始前 GET_LOCK、在事务提交/回滚之后才 RELEASE_LOCK（顺序不能颠倒）。
     *
     * 为什么锁要走独立连接而不是 MyBatis 连接：GET_LOCK 绑定连接；
     * 若用事务所在连接，Spring 提交时归还连接会立刻放锁，存在「锁已放、事务未提交」的窗口，
     * 后到的事务取号/点状态时读不到刚提交的数据，会算出重复号、漏看刚翻走的状态。
     * 独立连接持锁可把锁保到提交之后。
     */
    private <T> T inWriteLock(Supplier<T> action) {
        Connection lockConn;
        try {
            lockConn = dataSource.getConnection();
        } catch (SQLException e) {
            throw new BizException("系统繁忙，请稍后重试");
        }
        try {
            if (!namedLock(lockConn, true)) {
                throw new BizException("系统繁忙，请稍后重试");
            }
            try {
                return action.get();
            } finally {
                // 此时 action 内的事务已提交（或回滚），放锁后后到者必能看到本次写入
                namedLock(lockConn, false);
            }
        } finally {
            try {
                lockConn.close();
            } catch (SQLException ignored) {
                // 连接关闭会自动释放其上的命名锁，不影响主流程
            }
        }
    }

    /** GET_LOCK / RELEASE_LOCK；返回 MySQL 结果（1 成功）。 */
    private boolean namedLock(Connection conn, boolean get) {
        String sql = get ? "SELECT GET_LOCK(?, ?)" : "SELECT RELEASE_LOCK(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WRITE_LOCK);
            if (get) {
                ps.setInt(2, LOCK_WAIT_SECONDS);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int r = rs.getInt(1);
                    return !rs.wasNull() && r == 1;
                }
                return false;
            }
        } catch (SQLException e) {
            if (get) {
                throw new BizException("系统繁忙，请稍后重试");
            }
            return false;
        }
    }

    /**
     * 阻塞 DB 调用 → 响应式链路桥接器：先从 Reactor Context 取操作人，再切到 boundedElastic，
     * 操作人放进 AuditContextHolder 供审计填充（与当票/赎当模块同一套约定，顺序不能颠倒）。
     */
    private <T> Mono<T> blocking(Supplier<T> supplier) {
        return Mono.deferContextual(ctx -> {
            String operator = ReactiveOperatorContext.getOperator(ctx);
            return Mono.fromCallable(() -> {
                AuditContextHolder.setOperator(operator);
                try {
                    return supplier.get();
                } finally {
                    AuditContextHolder.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
