using System.Text.RegularExpressions;
namespace Fixture;
public sealed class SummaryService
{
    public Summary Summarize(string? raw)
    {
        var tokens = (raw ?? "1,2,3").Split(',');
        if (tokens.Length > 20) throw new ArgumentException("invalid-values");
        var items = new List<int>();
        foreach (var token in tokens)
        {
            if (!Regex.IsMatch(token, "^[0-9]{1,10}$") || !long.TryParse(token, out var value) || value < 0 || value > 10000)
                throw new ArgumentException("invalid-values");
            items.Add((int)value);
        }
        return new Summary(items);
    }
}
