use serde_json::{json, Value};
#[derive(Default)]
pub struct Totals {
    files: u64,
    records: u64,
    selected: u64,
    measurements: u64,
    events: u64,
    bytes: u64,
    sum: i128,
    min: Option<i64>,
    max: Option<i64>,
}
impl Totals {
    pub fn add(&mut self, value: &Value) -> Result<(), (i32, String)> {
        self.files += 1;
        self.records += value["records"].as_u64().unwrap();
        self.selected += value["selected"].as_u64().unwrap();
        self.measurements += value["selectedMeasurements"].as_u64().unwrap();
        self.events += value["selectedEvents"].as_u64().unwrap();
        self.bytes += value["bytes"].as_u64().unwrap();
        self.sum += value["sum"].as_i64().unwrap() as i128;
        if let Some(n) = value["min"].as_i64() {
            self.min = Some(self.min.map_or(n, |old| old.min(n)));
        }
        if let Some(n) = value["max"].as_i64() {
            self.max = Some(self.max.map_or(n, |old| old.max(n)));
        }
        Ok(())
    }
    pub fn json(&self) -> Value {
        json!({"files":self.files,"records":self.records,"selected":self.selected,"measurements":self.measurements,"events":self.events,"bytes":self.bytes,"sum":self.sum.to_string(),"min":self.min,"max":self.max})
    }
}
