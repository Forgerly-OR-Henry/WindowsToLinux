package gold.debug.windowstolinux.acceptance.complex.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record QuoteResponse(
        UUID id,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal tax,
        BigDecimal total,
        int itemCount
) {
}
