package com.bankflow.transfer.controller;

import com.bankflow.auth.security.AuthenticatedUser;
import com.bankflow.common.exception.BankFlowException;
import com.bankflow.transfer.dto.CreateTransferRequest;
import com.bankflow.transfer.dto.TransferResponse;
import com.bankflow.transfer.dto.TransferResult;
import com.bankflow.transfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @Operation(
            summary = "Transfer money",
            description = """
                    Debits the source and credits the destination in one database transaction.
                    201 on the first success. 200 when the same Idempotency-Key repeats the same request.
                    409 when the same key is reused for a different request.
                    """
    )
    @ApiResponse(responseCode = "201", description = "Transfer created")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of the original transfer")
    @ApiResponse(responseCode = "400", description = "Validation or business rule failure", content = @Content(schema = @Schema(implementation = com.bankflow.common.response.ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "404", description = "Source or destination account is not available to the caller")
    @ApiResponse(responseCode = "409", description = "Idempotency key reused for a different request")
    public ResponseEntity<TransferResponse> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Parameter(
                    name = "Idempotency-Key",
                    description = "Required. 1 to 128 characters. Scoped to the authenticated user.",
                    required = true,
                    example = "transfer-2026-09-28-0001"
            )
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request
    ) {
        TransferResult result = transferService.transfer(requireUser(principal), idempotencyKey, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.transfer());
    }

    private AuthenticatedUser requireUser(AuthenticatedUser principal) {
        if (principal == null) {
            throw new BankFlowException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        return principal;
    }
}
