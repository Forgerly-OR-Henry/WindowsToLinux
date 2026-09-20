use crate::model::Summary;
pub fn summarize(raw: Option<&str>) -> Result<Summary, &'static str> {
    let mut items = Vec::new();
    for token in raw.unwrap_or("1,2,3").split(',') {
        if token.is_empty() || token.len() > 10 || !token.bytes().all(|c| c.is_ascii_digit()) || items.len() == 20 {
            return Err("invalid-values");
        }
        let item = token.parse::<u32>().map_err(|_| "invalid-values")?;
        if item > 10000 { return Err("invalid-values"); }
        items.push(item);
    }
    Ok(Summary::new(items))
}
