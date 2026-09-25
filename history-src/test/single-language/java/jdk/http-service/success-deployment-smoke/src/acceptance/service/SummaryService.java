package acceptance.service;

import java.util.ArrayList;
import java.util.List;

import acceptance.model.Summary;

public final class SummaryService {
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
        return new Summary(items);
    }
}
