using Fixture;
var configuration = FixtureConfiguration.Load();
var app = WebApplication.CreateBuilder(args).Build();
FixtureEndpoints.Map(app, configuration);
app.Run($"http://0.0.0.0:{configuration.Port}");
