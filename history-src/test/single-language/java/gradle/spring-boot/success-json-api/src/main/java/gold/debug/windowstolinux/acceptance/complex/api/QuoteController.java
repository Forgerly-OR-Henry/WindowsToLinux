package gold.debug.windowstolinux.acceptance.complex.api;

import java.util.UUID;

import gold.debug.windowstolinux.acceptance.complex.domain.QuoteRequest;
import gold.debug.windowstolinux.acceptance.complex.domain.QuoteResponse;
import gold.debug.windowstolinux.acceptance.complex.service.QuoteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quotes")
class QuoteController {
    private final QuoteService quoteService;

    QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    QuoteResponse create(@Valid @RequestBody QuoteRequest request) {
        return quoteService.create(request);
    }

    @GetMapping("/{id}")
    QuoteResponse find(@PathVariable UUID id) {
        return quoteService.find(id);
    }
}
