package com.dh.product.service;

/**
 * 판매 정책상 지금 살 수 없음(product.api#97) — 판매자 정지·해지, 판매 기간 밖, 1회 최대 구매 수량 초과.
 *
 * <p>고객에게 그대로 보이는 오류라 완성된 문구 대신 메시지 키만 들고 나가고, 실제 문구는
 * ApiExceptionHandler 가 요청 로케일(ko/en/zh/ja)로 해석한다(gateway#68, order.api 의 OrderStateException 과
 * 같은 방식). IllegalStateException 을 상속하는 건 409(상태 충돌) 매핑을 유지하기 위해서다.
 */
public class PurchaseRuleViolationException extends IllegalStateException {

    private final String messageKey;
    private final transient Object[] messageArgs;

    public PurchaseRuleViolationException(String messageKey, Object... messageArgs) {
        super(messageKey);
        this.messageKey = messageKey;
        this.messageArgs = messageArgs;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getMessageArgs() {
        return messageArgs;
    }
}
