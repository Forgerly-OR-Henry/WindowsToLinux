package gold.debug.windowstolinux.acceptance.complex.service;

import java.util.ArrayList;
import java.util.List;

import gold.debug.windowstolinux.acceptance.complex.model.Summary;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

@Service
public final class SummaryService {
    private final Validator validator;
    public SummaryService(Validator validator) {
        this.validator = validator;
    }
    public record Input(@Size(min = 1, max = 20) List<@Min(0) @Max(10000) Integer> items) {
    }
    public Summary summarize(String raw) {
        if (raw == null)
            raw = "1,2,3";
        String[] tokens = raw.split(",", -1);
        if (tokens.length > 20)
            throw new IllegalArgumentException("invalid-values");
        List<Integer> items = new ArrayList<>();
        for (String part : tokens) {
            if (!part.matches("[0-9]{1,10}"))
                throw new IllegalArgumentException("invalid-values");
            long number = Long.parseLong(part);
            if (number > 10000)
                throw new IllegalArgumentException("invalid-values");
            items.add((int) number);
        }
        if (!validator.validate(new Input(items)).isEmpty())
            throw new IllegalArgumentException("invalid-values");
        return new Summary(items);
    }
}
