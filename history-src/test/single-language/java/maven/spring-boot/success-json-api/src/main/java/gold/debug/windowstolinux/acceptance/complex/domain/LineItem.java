package gold.debug.windowstolinux.acceptance.complex.domain;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LineItem(@NotBlank String sku, @Positive int quantity, @NotNull @Positive BigDecimal unitPrice) {
}
