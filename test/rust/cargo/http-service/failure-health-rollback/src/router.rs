use crate::{config::Configuration, service};
pub struct Response { pub status: u16, pub content_type: &'static str, pub body: String }
fn decode(raw: &str) -> Result<String, &'static str> {
    let bytes = raw.as_bytes(); let mut value = Vec::new(); let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            if index + 2 >= bytes.len() { return Err("invalid-values"); }
            let high = (bytes[index + 1] as char).to_digit(16).ok_or("invalid-values")?;
            let low = (bytes[index + 2] as char).to_digit(16).ok_or("invalid-values")?;
            value.push((high * 16 + low) as u8); index += 3;
        } else { value.push(if bytes[index] == b'+' { b' ' } else { bytes[index] }); index += 1; }
    }
    String::from_utf8(value).map_err(|_| "invalid-values")
}
pub fn route(configuration: &Configuration, target: &str) -> Response {
    let mut response = Response { status: configuration.status, content_type: "text/plain; charset=utf-8", body: configuration.label.clone() };
    if response.status == 503 { return response; }
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    if path == "/api/summary" || configuration.mode == "json" {
        let result = (|| {
            let mut raw = None;
            if path == "/api/summary" {
                for field in query.split('&') {
                    let (key, value) = field.split_once('=').unwrap_or((field, ""));
                    if decode(key)? == "values" { raw = Some(decode(value)?); break; }
                }
            }
            service::summarize(raw.as_deref())?.json().map_err(|_| "serialization-failed")
        })();
        match result {
            Ok(body) => { response.content_type = "application/json; charset=utf-8"; response.body = body; }
            Err(message) => { response.status = if message == "serialization-failed" { 500 } else { 400 }; response.body = message.into(); }
        }
    }
    response
}
