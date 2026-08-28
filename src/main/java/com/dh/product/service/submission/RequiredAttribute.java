package com.dh.product.service.submission;

/**
 * {@code category_requirements.required_attributes} JSON 배열의 원소 하나.
 * 고시는 개정되므로 이 정의는 코드 상수가 아니라 DB 에 있다(product.api#30).
 */
public record RequiredAttribute(String code, String label, boolean required) {
}
