mod config;
mod report;
mod worker;
use serde_json::json;
use std::{
    fs,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Mutex,
    },
};
fn execute() -> Result<i32, (i32, String)> {
    let c = config::Config::parse()?;
    let next = AtomicUsize::new(0);
    let results = Mutex::new(vec![None; c.inputs.len()]);
    std::thread::scope(|scope| {
        for _ in 0..c.jobs {
            let c = &c;
            let next = &next;
            let results = &results;
            scope.spawn(move || loop {
                let i = next.fetch_add(1, Ordering::Relaxed);
                if i >= c.inputs.len() {
                    break;
                }
                let mut args = vec!["--input".into(), c.inputs[i].clone()];
                args.extend(c.forwarded.clone());
                let result = worker::invoke(&c.helper, &args, c.timeout);
                results.lock().unwrap()[i] = Some(result);
            });
        }
    });
    let mut items = vec![];
    let mut exit = 0;
    let mut totals = report::Totals::default();
    for (input, result) in c.inputs.iter().zip(results.into_inner().unwrap()) {
        match result.unwrap() {
            Ok(value) => {
                totals.add(&value)?;
                items.push(json!({"file":input,"status":"complete","result":value}));
            }
            Err((code, message)) => {
                exit = exit.max(code);
                let error =
                    serde_json::from_str::<serde_json::Value>(&message).unwrap_or_else(|_| json!({"message":message}));
                eprintln!("{input}: {error}");
                items.push(json!({"file":input,"status":"error","exitCode":code,"error":error}));
            }
        }
    }
    let report = json!({"protocolVersion":2,"tool":"binary-inspector","status":if exit==0{"complete"}else{"partial"},"items":items,"summary":totals.json()});
    let text = serde_json::to_string_pretty(&report).map_err(|e| (4, e.to_string()))?;
    if text.len() > 16 * 1024 * 1024 {
        return Err((4, "批量报告超过 16 MiB 输出限制".into()));
    }
    if let Some(path) = c.output {
        fs::write(path, text.as_bytes()).map_err(|e| (3, e.to_string()))?;
    }
    if c.format == "json" {
        println!("{text}");
    } else {
        println!(
            "binary-inspector: {} 个文件\n{}",
            items.len(),
            serde_json::to_string_pretty(&totals.json()).unwrap()
        );
    }
    Ok(exit)
}
fn main() {
    let code = match execute() {
        Ok(c) => c,
        Err((c, e)) => {
            eprintln!("{e}");
            c
        }
    };
    std::process::exit(code);
}
