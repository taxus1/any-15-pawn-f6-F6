package com.somepro.domain.forfeit.model;

/**
 * 绝当处置单翻单条件（不可变值对象）。
 *
 * 按当票、按处置方式随意拼：任一项为 null 即不参与过滤；全 null 翻整份处置单。
 * 处置方式在进入本对象前已由 {@link DisposeMethod#ofCode} 解析，非法写法在解析阶段挡回。
 *
 * @param ticketId      按哪张当票翻；null 不按当票筛
 * @param disposeMethod 按哪种处置方式翻；null 不按处置方式筛
 */
public record ForfeitQuery(Long ticketId, DisposeMethod disposeMethod) {

    public static ForfeitQuery of(Long ticketId, DisposeMethod disposeMethod) {
        return new ForfeitQuery(ticketId, disposeMethod);
    }
}
