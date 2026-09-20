using Microsoft.Data.Sqlite;
using System.Globalization;
namespace AssetLending;

public sealed partial class AssetStore
{
    static object Loan(SqliteConnection c, SqliteTransaction? tx, long id) { var loan = One(c, tx, "SELECT id,borrower,due_date AS dueDate,purpose,status,created FROM loans WHERE id=@p0", id); loan["items"] = Rows(c, tx, "SELECT a.id AS assetId,a.name,a.serial,a.status AS assetStatus,i.returned,i.condition,i.returned_at AS returnedAt FROM loan_items i JOIN assets a ON a.id=i.asset_id WHERE i.loan_id=@p0 ORDER BY a.id", id); loan["events"] = Rows(c, tx, "SELECT action,actor,detail,created FROM events WHERE loan_id=@p0 ORDER BY id", id); return loan; }
    public object GetLoan(long id) { using var c = Open(); return Loan(c, null, id); }
    public object ListLoans(string status, string borrower, bool overdue, int offset, int limit) { using var c = Open(); const string where = " FROM loans WHERE (@p0='' OR status=@p0) AND (@p1='' OR borrower=@p1) AND (@p2=0 OR (status IN ('checked_out','partially_returned') AND due_date<date('now')))"; return new { items = Rows(c, null, "SELECT id,borrower,due_date AS dueDate,purpose,status,created,(SELECT count(*) FROM loan_items WHERE loan_id=loans.id) AS itemCount" + where + " ORDER BY id DESC LIMIT @p3 OFFSET @p4", status, borrower, overdue ? 1 : 0, limit, offset), total = Scalar(c, null, "SELECT count(*)" + where, status, borrower, overdue ? 1 : 0), offset, limit }; }
    public object CreateLoan(LoanInput input)
    {
        Actor(input.Actor); Actor(input.Borrower); Text(input.Purpose, 300, "用途");
        if (input.AssetIds is null || input.AssetIds.Length is < 1 or > 20 || input.AssetIds.Distinct().Count() != input.AssetIds.Length) throw new BusinessError(400, "借用单须含1..20件不同资产");
        if (!DateOnly.TryParseExact(input.DueDate, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _)) throw new BusinessError(400, "期限须为有效的 yyyy-MM-dd 日期");
        return Request(input.RequestId, new { action = "create", input }, (c, tx) => { foreach (long asset in input.AssetIds) One(c, tx, "SELECT id FROM assets WHERE id=@p0", asset); Exec(c, tx, "INSERT INTO loans(borrower,due_date,purpose,status) VALUES(@p0,@p1,@p2,'draft')", input.Borrower, input.DueDate, input.Purpose.Trim()); long id = Scalar(c, tx, "SELECT last_insert_rowid()"); foreach (long asset in input.AssetIds) Exec(c, tx, "INSERT INTO loan_items(loan_id,asset_id) VALUES(@p0,@p1)", id, asset); Event(c, tx, id, null, "created", input.Actor, input.Purpose); return Loan(c, tx, id); });
    }
    public object Change(long id, string action, ActionInput input)
    {
        Actor(input.Actor); if (input.Note is null || input.Note.Length > 500) throw new BusinessError(400, "备注过长");
        if (action == "return" && (input.Items is null || input.Items.Length is < 1 or > 20 || input.Items.Select(i => i.AssetId).Distinct().Count() != input.Items.Length || input.Items.Any(i => i.Condition is not ("good" or "damaged") || i.Note is null || i.Note.Length > 500))) throw new BusinessError(400, "归还明细无效");
        return Request(input.RequestId, new { id, action, input }, (c, tx) =>
        {
            string status = (string)One(c, tx, "SELECT status FROM loans WHERE id=@p0", id)["status"]!;
            string next = action switch { "submit" when status == "draft" => "submitted", "approve" when status == "submitted" => "approved", "reject" when status == "submitted" => "rejected", "checkout" when status == "approved" => "checked_out", "return" when status is "checked_out" or "partially_returned" => "partially_returned", _ => throw new BusinessError(409, "借用单状态不允许此操作") };
            var items = Rows(c, tx, "SELECT asset_id FROM loan_items WHERE loan_id=@p0 ORDER BY asset_id", id);
            if (action == "approve")
            {
                foreach (var item in items) { long asset = Convert.ToInt64(item["asset_id"]); if (Exec(c, tx, "UPDATE assets SET status='reserved',loan_id=@p0 WHERE id=@p1 AND status='available'", id, asset) != 1) throw new BusinessError(409, "整单审批失败：包含不可用资产"); Event(c, tx, id, asset, "reserved", input.Actor, ""); Fault("approval-reserved"); }
            }
            if (action == "checkout") foreach (var item in items) { long asset = Convert.ToInt64(item["asset_id"]); if (Exec(c, tx, "UPDATE assets SET status='lent' WHERE id=@p0 AND loan_id=@p1 AND status='reserved'", asset, id) != 1) throw new BusinessError(409, "领用占用状态冲突"); Event(c, tx, id, asset, "checkout", input.Actor, ""); }
            if (action == "return")
            {
                foreach (var item in input.Items!) { if (Exec(c, tx, "UPDATE loan_items SET returned=1,condition=@p0,returned_at=CURRENT_TIMESTAMP WHERE loan_id=@p1 AND asset_id=@p2 AND returned=0", item.Condition, id, item.AssetId) != 1) throw new BusinessError(409, "资产不在借用单内或已经归还"); if (Exec(c, tx, "UPDATE assets SET status=@p0,loan_id=NULL WHERE id=@p1 AND loan_id=@p2 AND status='lent'", item.Condition == "damaged" ? "maintenance" : "available", item.AssetId, id) != 1) throw new BusinessError(409, "归还状态冲突"); if (item.Condition == "damaged") Exec(c, tx, "INSERT INTO maintenance(asset_id,loan_id,status,note) VALUES(@p0,@p1,'open',@p2)", item.AssetId, id, item.Note); Event(c, tx, id, item.AssetId, "returned", input.Actor, item.Condition + ": " + item.Note); }
                if (Scalar(c, tx, "SELECT count(*) FROM loan_items WHERE loan_id=@p0 AND returned=0", id) == 0) next = "closed";
            }
            Exec(c, tx, "UPDATE loans SET status=@p0 WHERE id=@p1", next, id); Event(c, tx, id, null, action, input.Actor, input.Note); return Loan(c, tx, id);
        });
    }
    public object FinishRepair(long id, ActionInput input) { Actor(input.Actor); Text(input.Note, 500, "维修说明"); return Request(input.RequestId, new { action = "repair", id, input }, (c, tx) => { var work = One(c, tx, "SELECT asset_id,loan_id,status FROM maintenance WHERE id=@p0", id); if ((string)work["status"]! != "open") throw new BusinessError(409, "维修工单已经完成"); long asset = Convert.ToInt64(work["asset_id"]), loan = Convert.ToInt64(work["loan_id"]); if (Exec(c, tx, "UPDATE assets SET status='available' WHERE id=@p0 AND status='maintenance'", asset) != 1) throw new BusinessError(409, "资产维修状态冲突"); Exec(c, tx, "UPDATE maintenance SET status='completed',completed=CURRENT_TIMESTAMP,note=note||char(10)||@p0 WHERE id=@p1", input.Note, id); Event(c, tx, loan, asset, "repair_completed", input.Actor, input.Note); return One(c, tx, "SELECT id,status,asset_id AS assetId,note FROM maintenance WHERE id=@p0", id); }); }
}
