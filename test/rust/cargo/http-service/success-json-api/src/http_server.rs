use crate::{config::Configuration, router};
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::time::Duration;
fn handle(mut stream: TcpStream, configuration: &Configuration) -> io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(3)))?;
    stream.set_write_timeout(Some(Duration::from_secs(3)))?;
    let mut request = Vec::new(); let mut chunk = [0_u8; 1024];
    while request.len() < 8192 && !request.windows(4).any(|part| part == b"\r\n\r\n") {
        let count = stream.read(&mut chunk)?;
        if count == 0 { return Ok(()); }
        request.extend_from_slice(&chunk[..count]);
    }
    let text = String::from_utf8_lossy(&request);
    let target = text.split_whitespace().nth(1).unwrap_or("/");
    let response = router::route(configuration, target);
    let reason = match response.status { 200 => "OK", 503 => "Service Unavailable", _ => "Bad Request" };
    write!(stream, "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        response.status, reason, response.content_type, response.body.len(), response.body)
}
pub fn serve(configuration: &Configuration) -> io::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", configuration.port))?;
    for stream in listener.incoming() {
        match stream {
            Ok(stream) => { if let Err(error) = handle(stream, configuration) { eprintln!("request: {error}"); } }
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        }
    }
    Ok(())
}
