use std::{
    io::Read,
    path::Path,
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};
pub fn invoke(
    helper: &Path,
    args: &[String],
    timeout: u64,
) -> Result<serde_json::Value, (i32, String)> {
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
        out.take(8 * 1024 * 1024 + 1)
            .read_to_end(&mut data)
            .map(|_| data)
    });
    let stderr = thread::spawn(move || {
        let mut data = Vec::new();
        err.take(1024 * 1024 + 1)
            .read_to_end(&mut data)
            .map(|_| data)
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
    if !status.success() {
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
    if records.len() != 3 {
        return Err((4, "子程序协议缺少开始、汇总或结束标记".into()));
    }
    for (index, kind) in ["start", "summary", "end"].iter().enumerate() {
        let value = &records[index];
        if value["protocolVersion"].as_u64() != Some(2)
            || value["component"].as_str() != Some("cpp-log")
            || value["sequence"].as_u64() != Some(index as u64)
            || value["type"].as_str() != Some(kind)
        {
            return Err((4, "子程序协议版本、类型或序号不匹配".into()));
        }
    }
    let value = &records[1];
    let invalid = || (4, "子程序协议字段缺失或数量不一致".into());
    let lines = value["lines"].as_u64().ok_or_else(invalid)?;
    let matched = value["matched"].as_u64().ok_or_else(invalid)?;
    let bad = value["invalidCount"].as_u64().ok_or_else(invalid)?;
    if matched > lines
        || bad > lines - matched
        || records[2]["records"].as_u64() != Some(lines)
        || records[2]["messages"].as_u64() != Some(3)
    {
        return Err(invalid());
    }
    for name in ["levels", "minutes", "services", "errors"] {
        let values = value[name].as_object().ok_or_else(invalid)?;
        let mut sum = 0u64;
        for (key, count) in values {
            let n = count.as_u64().ok_or_else(invalid)?;
            if n > matched || key.len() > 8400 {
                return Err(invalid());
            }
            sum = sum.checked_add(n).ok_or_else(invalid)?;
        }
        if (name != "errors" && sum != matched)
            || (name == "errors" && sum != value["levels"]["ERROR"].as_u64().ok_or_else(invalid)?)
        {
            return Err(invalid());
        }
    }
    if value["levels"].as_object().unwrap().len() != 4
        || ["DEBUG", "INFO", "WARN", "ERROR"]
            .iter()
            .any(|key| !value["levels"][key].is_u64())
    {
        return Err(invalid());
    }
    let samples = value["invalid"].as_array().ok_or_else(invalid)?;
    if samples.len() as u64 != bad.min(20) {
        return Err(invalid());
    }
    for sample in samples {
        if !sample["line"].as_u64().is_some_and(|n| n > 0 && n <= lines)
            || !sample["reason"].is_string()
        {
            return Err(invalid());
        }
    }
    let mut result = value.clone();
    for name in ["protocolVersion", "component", "type", "sequence"] {
        result.as_object_mut().unwrap().remove(name);
    }
    Ok(result)
}
