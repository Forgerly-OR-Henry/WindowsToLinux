package gold.debug.windowstolinux.acceptance.complex.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.util.List;

import gold.debug.windowstolinux.acceptance.complex.domain.LineItem;
import gold.debug.windowstolinux.acceptance.complex.domain.QuoteRequest;
import org.junit.jupiter.api.Test;

class QuoteServiceTest {
    @Test
    void pricesDiscountAndTaxAndRetainsTheQuote() {
        QuoteService service = new QuoteService();

        var quote = service.create(new QuoteRequest(List.of(new LineItem("book", 2, new BigDecimal("12.50")),
                new LineItem("pen", 3, new BigDecimal("2.00"))), "SPRING10"));

        assertEquals(new BigDecimal("31.00"), quote.subtotal());
        assertEquals(new BigDecimal("3.10"), quote.discount());
        assertEquals(new BigDecimal("2.51"), quote.tax());
        assertEquals(new BigDecimal("30.41"), quote.total());
        assertSame(quote, service.find(quote.id()));
    }
}
