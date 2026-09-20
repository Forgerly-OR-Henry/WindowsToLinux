namespace AssetLending;

public record AssetInput(string Name, long CategoryId, string Serial);
public record CategoryInput(string Name);
public record LoanInput(long[] AssetIds, string Borrower, string DueDate, string Purpose, string Actor, string RequestId);
public record ReturnItem(long AssetId, string Condition, string Note);
public record ActionInput(string RequestId, string Actor, ReturnItem[]? Items = null, string Note = "");
public sealed class BusinessError(int status, string message) : Exception(message)
{
    public int Status { get; } = status;
}
