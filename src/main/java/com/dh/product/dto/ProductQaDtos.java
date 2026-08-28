package com.dh.product.dto;

import java.util.List;

import com.dh.product.dto.ProductDtos.ProductSummaryResponse;

import jakarta.validation.constraints.NotBlank;

public class ProductQaDtos {

    public record ProductQaRequest(@NotBlank String question) {
    }

    public record ProductQaResponse(String answer, List<ProductSummaryResponse> products) {
    }
}
