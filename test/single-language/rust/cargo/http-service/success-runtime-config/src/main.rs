mod config;
mod http_server;
mod model;
mod router;
mod service;
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let configuration = config::Configuration::load()?;
    http_server::serve(&configuration)?;
    Ok(())
}
