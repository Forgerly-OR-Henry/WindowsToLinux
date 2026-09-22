using Newtonsoft.Json;
namespace Fixture;

public sealed class Summary
{
    [JsonProperty("status")] public string Status => "ok";
    [JsonProperty("items")] public IReadOnlyList<int> Items { get; }
    [JsonProperty("total")] public int Total => Items.Sum();
    public Summary(IEnumerable<int> items) { Items = Array.AsReadOnly(items.ToArray()); }
    public string ToJson() => JsonConvert.SerializeObject(this);
}
