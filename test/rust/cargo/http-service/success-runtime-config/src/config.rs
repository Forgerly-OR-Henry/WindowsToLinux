use std::env;
pub struct Configuration { pub port: u16, pub mode: &'static str, pub status: u16, pub label: String }
impl Configuration {
    pub fn load() -> Result<Self, Box<dyn std::error::Error>> {
        let raw = env::var("PORT")?;
        let port = raw.parse::<u16>()?;
        if port == 0 || !raw.bytes().all(|c| c.is_ascii_digit()) { return Err("Invalid PORT".into()); }
        let mode = "config";
        let label = if mode == "config" { env::var("FIXTURE_LABEL").unwrap_or_else(|_| "runtime-config-default".into()) }
            else { "deployment-smoke-ok".into() };
        Ok(Self { port, mode, status: 200, label })
    }
}
