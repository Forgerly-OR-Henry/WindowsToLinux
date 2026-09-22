use serde::Serialize;
#[derive(Serialize)]
pub struct Summary {
    status: &'static str,
    items: Vec<u32>,
    total: u32,
}
impl Summary {
    pub fn new(items: Vec<u32>) -> Self {
        let total = items.iter().sum();
        Self {
            status: "ok",
            items,
            total,
        }
    }
    pub fn json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}
