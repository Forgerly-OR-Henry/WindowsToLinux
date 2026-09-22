package gold.debug.windowstolinux.acceptance.complex.domain;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record QuoteRequest(@NotEmpty List<@Valid LineItem> items, String couponCode) {
}
