use std::{
    io::Read,
    path::Path,
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};
pub fn invoke(helper: &Path, args: &[String], timeout: u64) -> Result<serde_json::Value, (i32, String)> {
    let mut child = Command::new(helper)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| (4, format!("无法启动配套程序: {e}")))?;
    let out = child.stdout.take().unwrap();
    let err = child.stderr.take().unwrap();
    let stdout = thread::spawn(move || {
        let mut data = Vec::new();
        out.take(8 * 1024 * 1024 + 1).read_to_end(&mut data).map(|_| data)
    });
    let stderr = thread::spawn(move || {
        let mut data = Vec::new();
        err.take(1024 * 1024 + 1).read_to_end(&mut data).map(|_| data)
    });
    let start = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Ok(status),
            Ok(None) => {}
            Err(e) => break Err(format!("子程序状态读取失败: {e}")),
        };
        if start.elapsed() > Duration::from_millis(timeout) {
            break Err("子程序执行超时".to_string());
        }
        thread::sleep(Duration::from_millis(10));
    };
    if status.is_err() {
        let _ = child.kill();
        let _ = child.wait();
    }
    let output = stdout
        .join()
        .map_err(|_| (4, "读取线程失败".into()))?
        .map_err(|e| (4, e.to_string()))?;
    let errors = stderr
        .join()
        .map_err(|_| (4, "诊断线程失败".into()))?
        .map_err(|e| (4, e.to_string()))?;
    let status = status.map_err(|e| (4, e))?;
    if !status.success() && output.is_empty() {
        let code = status.code().unwrap_or(4);
        return Err((
            if code == 2 || code == 3 { code } else { 4 },
            String::from_utf8_lossy(&errors).trim().to_string(),
        ));
    }
    if output.len() > 8 * 1024 * 1024 || errors.len() > 1024 * 1024 {
        return Err((4, "子程序输出过大".into()));
    }
    let records: Vec<serde_json::Value> = output
        .split(|b| *b == b'\n')
        .filter(|line| !line.is_empty())
        .map(serde_json::from_slice)
        .collect::<Result<_, _>>()
        .map_err(|e| (4, format!("子程序JSON无效: {e}")))?;
    let invalid = || (4, "子程序协议字段、数量或结束标记不一致".into());
    if records.len() != 3 {
        return Err(invalid());
    }
    for (i, record) in records.iter().enumerate() {
        if record["protocolVersion"].as_u64() != Some(2)
            || record["component"].as_str() != Some("c-binary")
            || record["sequence"].as_u64() != Some(i as u64)
        {
            return Err((4, "子程序协议版本或序号不匹配".into()));
        }
    }
    if records[0]["type"] != "start"
        || records[2]["type"] != "end"
        || records[2]["messages"].as_u64() != Some(3)
        || records[2]["records"].as_u64().is_none()
    {
        return Err(invalid());
    }
    let value = &records[1];
    if value["type"] == "error" {
        let code = value["exitCode"].as_i64().ok_or_else(invalid)?;
        let e = &value["error"];
        if !matches!(code, 2 | 3)
            || status.code() != Some(code as i32)
            || !e["message"].is_string()
            || !e["offset"].is_u64()
            || !(e["block"].is_null() || e["block"].as_u64().is_some_and(|v| v < 65536))
        {
            return Err(invalid());
        }
        return Err((code as i32, e.to_string()));
    }
    if !status.success() || value["type"] != "summary" {
        return Err(invalid());
    }
    for name in [
        "records",
        "measurements",
        "events",
        "selected",
        "selectedMeasurements",
        "selectedEvents",
    ] {
        if !value[name].as_u64().is_some_and(|n| n <= 1000000) {
            return Err(invalid());
        }
    }
    let n = |key: &str| value[key].as_u64().unwrap();
    if n("measurements") + n("events") != n("records")
        || n("selectedMeasurements") > n("measurements")
        || n("selectedEvents") > n("events")
        || n("selectedMeasurements") + n("selectedEvents") != n("selected")
        || records[2]["records"].as_u64() != Some(n("records"))
    {
        return Err(invalid());
    }
    if !value["blocks"].as_u64().is_some_and(|v| v <= 65536)
        || !value["bytes"].as_u64().is_some_and(|v| (28..=1073741824).contains(&v))
        || !value["eventBytes"]
            .as_u64()
            .is_some_and(|v| v <= n("selectedEvents") * 4096)
        || !value["flagsOr"].as_u64().is_some_and(|v| v <= 3)
        || !value["checksum"]
            .as_str()
            .is_some_and(|s| s.len() == 8 && s.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        return Err(invalid());
    }
    let sum = value["sum"].as_i64().ok_or_else(invalid)?;
    if n("selectedMeasurements") == 0 {
        if !value["min"].is_null() || !value["max"].is_null() || sum != 0 {
            return Err(invalid());
        }
    } else {
        let min = value["min"].as_i64().ok_or_else(invalid)?;
        let max = value["max"].as_i64().ok_or_else(invalid)?;
        if min > max
            || (sum as i128) < min as i128 * n("selectedMeasurements") as i128
            || (sum as i128) > max as i128 * n("selectedMeasurements") as i128
        {
            return Err(invalid());
        }
    }
    let samples = value["eventSamples"].as_array().ok_or_else(invalid)?;
    if samples.len() as u64 != n("selectedEvents").min(5) {
        return Err(invalid());
    }
    for sample in samples {
        if !sample["id"].as_u64().is_some_and(|n| n <= u32::MAX as u64)
            || !sample["text"].as_str().is_some_and(|s| s.len() <= 4096)
        {
            return Err(invalid());
        }
    }
    let mut result = value.clone();
    for key in ["protocolVersion", "component", "sequence", "type"] {
        result.as_object_mut().unwrap().remove(key);
    }
    Ok(result)
}
