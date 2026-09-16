package gold.debug.windowstolinux.acceptance.complex.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record QuoteRequest(@NotEmpty List<@Valid LineItem> items, String couponCode) {
}
