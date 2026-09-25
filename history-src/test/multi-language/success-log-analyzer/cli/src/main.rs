mod config;
mod input;
mod report;
mod worker;
use serde_json::json;
use std::{
    fs,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc, Mutex,
    },
};

fn execute() -> Result<i32, (i32, String)> {
    let c = config::Config::parse()?;
    let files = input::discover(&c.inputs);
    if files.is_empty() {
        return Err((2, "输入目录中没有文件".into()));
    }
    let next = AtomicUsize::new(0);
    let bytes = AtomicUsize::new(0);
    let results = Arc::new(Mutex::new(vec![None; files.len()]));
    std::thread::scope(|scope| {
        for _ in 0..c.jobs {
            let c = &c;
            let files = &files;
            let next = &next;
            let bytes = &bytes;
            let results = Arc::clone(&results);
            scope.spawn(move || loop {
                let index = next.fetch_add(1, Ordering::Relaxed);
                if index >= files.len() {
                    break;
                }
                let result = (|| {
                    let path = files[index].as_ref().map_err(Clone::clone)?;
                    let prepared = input::prepare(path, c, index)?;
                    let mut args = vec!["--input".into(), prepared.path.to_string_lossy().into_owned()];
                    args.extend(c.forwarded.clone());
                    let value = worker::invoke(&c.helper, &args, c.timeout)?;
                    let size = serde_json::to_vec(&value).map_err(|e| (4, e.to_string()))?.len();
                    if bytes.fetch_add(size, Ordering::Relaxed) + size > 16 * 1024 * 1024 {
                        return Err((4, "批量汇总数据超过 16 MiB 限制".into()));
                    }
                    Ok(value)
                })();
                results.lock().unwrap()[index] = Some(result);
            });
        }
    });
    let mut aggregate = report::Aggregate::default();
    let mut items = vec![];
    let mut exit = 0;
    for (file, result) in files.iter().zip(results.lock().unwrap().iter_mut()) {
        let name = file
            .as_ref()
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_else(|(_, e)| e.clone());
        match result.take().unwrap() {
            Ok(value) => match aggregate.merge(&value, c.max_keys) {
                Ok(()) => items.push(json!({"file":name,"status":"complete","result":value})),
                Err(message) => {
                    exit = 4;
                    items.push(json!({"file":name,"status":"error","exitCode":4,"error":message}));
                }
            },
            Err((code, message)) => {
                exit = exit.max(code);
                items.push(json!({"file":name,"status":"error","exitCode":code,"error":message}));
            }
        }
    }
    let value = json!({"protocolVersion":2,"tool":"log-analyzer","status":if exit==0{"complete"}else{"partial"},"items":items,"summary":aggregate.json(c.top)});
    let output = serde_json::to_string_pretty(&value).map_err(|e| (4, e.to_string()))?;
    if output.len() > 16 * 1024 * 1024 {
        return Err((4, "批量报告超过 16 MiB 输出限制".into()));
    }
    if let Some(path) = c.output {
        fs::write(path, output.as_bytes()).map_err(|e| (3, e.to_string()))?;
    }
    if c.format == "json" {
        println!("{output}");
    } else {
        println!(
            "log-analyzer: {} 个输入，匹配 {} 行，无法解析 {} 行",
            items.len(),
            aggregate.matched,
            aggregate.invalid
        );
        println!("{}", serde_json::to_string_pretty(&aggregate.json(c.top)).unwrap());
    }
    for item in &items {
        if item["status"] == "error" {
            eprintln!("{}: {}", item["file"], item["error"]);
        }
    }
    Ok(exit)
}
fn main() {
    let code = match execute() {
        Ok(code) => code,
        Err((code, error)) => {
            eprintln!("{error}");
            code
        }
    };
    std::process::exit(code);
}
