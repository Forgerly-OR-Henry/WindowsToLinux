use serde_json::{json, Value};
use std::collections::BTreeMap;
#[derive(Default, Clone)]
pub struct Aggregate {
    pub lines: u64,
    pub matched: u64,
    pub invalid: u64,
    levels: BTreeMap<String, u64>,
    minutes: BTreeMap<String, u64>,
    services: BTreeMap<String, u64>,
    errors: BTreeMap<String, u64>,
}
impl Aggregate {
    pub fn merge(&mut self, value: &Value, limit: usize) -> Result<(), String> {
        let mut next = self.clone();
        next.lines += value["lines"].as_u64().unwrap();
        next.matched += value["matched"].as_u64().unwrap();
        next.invalid += value["invalidCount"].as_u64().unwrap();
        for (name, target) in [
            ("levels", &mut next.levels),
            ("minutes", &mut next.minutes),
            ("services", &mut next.services),
            ("errors", &mut next.errors),
        ] {
            for (key, count) in value[name].as_object().unwrap() {
                *target.entry(key.clone()).or_default() += count.as_u64().unwrap();
            }
        }
        if next.minutes.len() + next.services.len() + next.errors.len() > limit {
            return Err("跨文件汇总超过 --max-keys，当前文件未加入汇总".into());
        }
        *self = next;
        Ok(())
    }
    pub fn json(&self, top: usize) -> Value {
        let mut ranking: Vec<_> = self.errors.iter().collect();
        ranking.sort_by(|a, b| b.1.cmp(a.1).then_with(|| a.0.cmp(b.0)));
        json!({"lines":self.lines,"matched":self.matched,"invalidCount":self.invalid,"levels":self.levels,"minutes":self.minutes,"services":self.services,"errors":self.errors,"topErrors":ranking.into_iter().take(top).map(|(group,count)|json!({"group":group,"count":count})).collect::<Vec<_>>()})
    }
}
