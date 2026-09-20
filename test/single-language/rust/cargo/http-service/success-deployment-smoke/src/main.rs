mod config;
mod model;
mod service;
mod router;
mod http_server;
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let configuration = config::Configuration::load()?;
    http_server::serve(&configuration)?;
    Ok(())
}
