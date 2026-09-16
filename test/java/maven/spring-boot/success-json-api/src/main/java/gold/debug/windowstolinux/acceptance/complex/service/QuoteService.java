package gold.debug.windowstolinux.acceptance.complex.service;

import gold.debug.windowstolinux.acceptance.complex.domain.LineItem;
import gold.debug.windowstolinux.acceptance.complex.domain.QuoteRequest;
import gold.debug.windowstolinux.acceptance.complex.domain.QuoteResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class QuoteService {
    private static final BigDecimal TAX_RATE = new BigDecimal("0.09");
    private final ConcurrentMap<UUID, QuoteResponse> quotes = new ConcurrentHashMap<>();

    public QuoteResponse create(QuoteRequest request) {
        BigDecimal subtotal = request.items().stream()
                .map(this::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = "SPRING10".equalsIgnoreCase(request.couponCode())
                ? subtotal.multiply(new BigDecimal("0.10")) : BigDecimal.ZERO;
        BigDecimal taxable = subtotal.subtract(discount);
        BigDecimal tax = taxable.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        UUID id = UUID.randomUUID();
        QuoteResponse response = new QuoteResponse(id, money(subtotal), money(discount), tax,
                money(taxable.add(tax)), request.items().size());
        quotes.put(id, response);
        return response;
    }

    public QuoteResponse find(UUID id) {
        QuoteResponse response = quotes.get(id);
        if (response == null) {
            throw new NoSuchElementException("quote not found");
        }
        return response;
    }

    private BigDecimal lineTotal(LineItem item) {
        return item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
