package com.somepro.interfaces.rest.forfeit.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 办理绝当入参（用户接口层）。
 *
 * 用可变 bean + @ModelAttribute：WebFlux 下 application/x-www-form-urlencoded 表单、
 * query string 都能直接绑定。处置时刻取服务端当下、处置单号由服务端生成，不接受外部指定。
 * 回款用字符串接，由应用层统一解析金额，避免空串/非数字绑定成一堆费解的框架异常。
 */
@Getter
@Setter
public class ForfeitCreateRequest {

    /** 处置的是哪张当票（t_pawn_ticket.id）。 */
    private Long ticketId;

    /** 处置方式：AUCTION 拍卖 / CONSIGN 变卖 / WRITE_OFF 核销。 */
    private String disposeMethod;

    /** 处置回款（元）：实际到手金额，可填 0，不能为负。 */
    private String recoverAmount;
}
