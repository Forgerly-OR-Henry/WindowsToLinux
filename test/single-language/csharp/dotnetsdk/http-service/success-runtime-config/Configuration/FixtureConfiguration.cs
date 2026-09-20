using System.Text.RegularExpressions;
namespace Fixture;
public sealed record FixtureConfiguration(int Port, string Mode, int Status, string Label)
{
    public static FixtureConfiguration Load()
    {
        var raw = Environment.GetEnvironmentVariable("PORT") ?? "";
        if (!Regex.IsMatch(raw, "^[0-9]{1,5}$") || !int.TryParse(raw, out var port) || port < 1 || port > 65535)
            throw new ArgumentException("Invalid PORT");
        var mode = "config";
        var label = mode == "config" ? Environment.GetEnvironmentVariable("FIXTURE_LABEL") ?? "runtime-config-default" : "deployment-smoke-ok";
        return new FixtureConfiguration(port, mode, 200, label);
    }
}
