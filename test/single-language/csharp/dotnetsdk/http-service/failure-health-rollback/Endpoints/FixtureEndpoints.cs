namespace Fixture;
public static class FixtureEndpoints
{
    public static void Map(WebApplication app, FixtureConfiguration configuration)
    {
        var service = new SummaryService();
        app.MapGet("/{**path}", (HttpRequest request) =>
        {
            if (configuration.Status == 503) return Results.Text(configuration.Label, "text/plain; charset=utf-8", statusCode: 503);
            if (request.Path == "/api/summary" || configuration.Mode == "json")
            {
                try
                {
                    string? raw = request.Path == "/api/summary" && request.Query.TryGetValue("values", out var values) ? values[0] ?? "" : null;
                    return Results.Text(service.Summarize(raw).ToJson(), "application/json; charset=utf-8");
                }
                catch (ArgumentException) { return Results.Text("invalid-values", statusCode: 400); }
            }
            return Results.Text(configuration.Label, "text/plain; charset=utf-8");
        });
    }
}
