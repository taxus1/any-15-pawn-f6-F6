package com.somepro.interfaces.rest.forfeit;

import com.somepro.application.forfeit.PawnForfeitAppService;
import com.somepro.common.Result;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.forfeit.converter.PawnForfeitVoConverter;
import com.somepro.interfaces.rest.forfeit.dto.ForfeitCreateRequest;
import com.somepro.interfaces.rest.forfeit.vo.PawnForfeitVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 绝当处置模块用户接口层：办理绝当、查看处置单、按当票/处置方式翻处置单。
 *
 * 只做协议适配（参数解析、VO 转换、Result 包装），业务编排在 {@link PawnForfeitAppService}。
 * 入参统一走 @ModelAttribute / @RequestParam：表单 / query string / x-www-form-urlencoded 都能接，
 * 便于柜台端直接调用。
 */
@RestController
@RequestMapping("/api/forfeit")
public class ForfeitController {

    private final PawnForfeitAppService pawnForfeitAppService;

    public ForfeitController(PawnForfeitAppService pawnForfeitAppService) {
        this.pawnForfeitAppService = pawnForfeitAppService;
    }

    /**
     * 办理绝当：只有当票在当、到期日已过且逾期满三十天才办得了；
     * 处置方式 AUCTION/CONSIGN/WRITE_OFF，回款写实际到手金额（可 0 不可负）；
     * 同一时点重复递交只成一次；处置单号服务端按 JD-年份-序号 生成；
     * 办成后当票转已绝当、当物转已绝当，两处状态一起翻。
     */
    @PostMapping("/create")
    public Mono<Result<PawnForfeitVO>> create(@ModelAttribute ForfeitCreateRequest request) {
        return pawnForfeitAppService.forfeit(request.getTicketId(), request.getDisposeMethod(),
                        request.getRecoverAmount())
                .map(PawnForfeitVoConverter::toVo)
                .map(Result::ok);
    }

    /** 查看绝当处置单：id 或 forfeitNo 任一指定。 */
    @GetMapping("/detail")
    public Mono<Result<PawnForfeitVO>> detail(@RequestParam(required = false) Long id,
                                              @RequestParam(required = false) String forfeitNo) {
        return pawnForfeitAppService.detail(id, forfeitNo)
                .map(PawnForfeitVoConverter::toVo)
                .map(Result::ok);
    }

    /**
     * 翻处置单：ticketId / disposeMethod 随意拼，都不填翻整份，一页一页走。
     * 每行带 forfeitNo 便于与拍卖行、寄卖行的回单对号，并带欠款本息与处置盈亏两笔对账数。
     */
    @GetMapping("/list")
    public Mono<Result<PageVO<PawnForfeitVO>>> list(@RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "20") int pageSize,
                                                    @RequestParam(required = false) Long ticketId,
                                                    @RequestParam(required = false) String disposeMethod) {
        return pawnForfeitAppService.page(pageNum, pageSize, ticketId, disposeMethod)
                .map(PawnForfeitVoConverter::toPageVo)
                .map(Result::ok);
    }
}
