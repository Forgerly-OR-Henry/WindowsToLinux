using AssetLending;
using Microsoft.Data.Sqlite;
var builder = WebApplication.CreateBuilder(args);
builder.Services.AddSingleton<AssetStore>();
builder.WebHost.UseUrls("http://" + (Environment.GetEnvironmentVariable("HOST") ?? "127.0.0.1") + ":" + (Environment.GetEnvironmentVariable("PORT") ?? "18121"));
var app = builder.Build();
app.Use(async (ctx, next) => { ctx.Response.Headers["X-Sample-Protocol"] = "2"; await next(ctx); });
app.Use(async (ctx, next) => { try { await next(ctx); } catch (BusinessError e) { ctx.Response.StatusCode = e.Status; await ctx.Response.WriteAsJsonAsync(new { error = e.Message }); } catch (SqliteException e) when (e.SqliteErrorCode == 19) { ctx.Response.StatusCode = 409; await ctx.Response.WriteAsJsonAsync(new { error = "记录违反唯一性或关联约束" }); } catch (Exception e) { app.Logger.LogError(e, "Request failed"); ctx.Response.StatusCode = 500; await ctx.Response.WriteAsJsonAsync(new { error = "业务存储操作失败" }); } });
static int Number(HttpRequest r, string key, int fallback, int min, int max) { string s = r.Query[key].ToString(); if (s == "") return fallback; if (!int.TryParse(s, out int n) || n < min || n > max) throw new BusinessError(400, key + "超出范围"); return n; }
app.MapGet("/healthz", (AssetStore s) => { s.Categories(); return new { status = "ok", component = "csharp-assets", version = 2 }; });
app.MapGet("/api/actors", () => AssetStore.Actors);
app.MapGet("/api/categories", (AssetStore s) => s.Categories());
app.MapPost("/api/categories", (CategoryInput input, AssetStore s) => s.AddCategory(input));
app.MapGet("/api/assets", (HttpRequest r, AssetStore s) => s.List(r.Query["q"].ToString(), r.Query["status"].ToString(), Number(r, "categoryId", 0, 0, int.MaxValue), Number(r, "offset", 0, 0, int.MaxValue), Number(r, "limit", 25, 1, 100)));
app.MapPost("/api/assets", (AssetInput input, AssetStore s) => s.Create(input));
app.MapGet("/api/assets/{id:long}/history", (long id, AssetStore s) => s.History(id));
app.MapGet("/api/stats", (AssetStore s) => s.Stats());
app.MapGet("/api/loans", (HttpRequest r, AssetStore s) => s.ListLoans(r.Query["status"].ToString(), r.Query["borrower"].ToString(), r.Query["overdue"] == "true", Number(r, "offset", 0, 0, int.MaxValue), Number(r, "limit", 25, 1, 100)));
app.MapPost("/api/loans", (LoanInput input, AssetStore s) => s.CreateLoan(input));
app.MapGet("/api/loans/{id:long}", (long id, AssetStore s) => s.GetLoan(id));
foreach (string operation in new[] { "submit", "approve", "reject", "checkout", "return" }) { var op = operation; app.MapPost("/api/loans/{id:long}/" + op, (long id, ActionInput input, AssetStore s) => s.Change(id, op, input)); }
app.MapGet("/api/maintenance", (AssetStore s) => s.Repairs());
app.MapPost("/api/maintenance/{id:long}/complete", (long id, ActionInput input, AssetStore s) => s.FinishRepair(id, input));
app.Run();
