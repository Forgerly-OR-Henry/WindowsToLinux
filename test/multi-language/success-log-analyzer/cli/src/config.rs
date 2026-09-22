use std::path::PathBuf;
#[derive(Clone)]
pub struct Config {
    pub inputs: Vec<PathBuf>,
    pub forwarded: Vec<String>,
    pub format: String,
    pub output: Option<PathBuf>,
    pub helper: PathBuf,
    pub timeout: u64,
    pub jobs: usize,
    pub max_keys: usize,
    pub max_bytes: u64,
    pub top: usize,
    pub temp: PathBuf,
}
impl Config {
    pub fn parse() -> Result<Self, (i32, String)> {
        let exe = std::env::current_exe().map_err(|e| (3, e.to_string()))?;
        let default = exe
            .ancestors()
            .nth(4)
            .ok_or((3, "安装目录不完整".into()))?
            .join("native/build")
            .join(if cfg!(windows) { "log-worker.exe" } else { "log-worker" });
        let mut c = Self {
            inputs: vec![],
            forwarded: vec![],
            format: "text".into(),
            output: None,
            helper: std::env::var_os("NATIVE_HELPER").map(PathBuf::from).unwrap_or(default),
            timeout: 30000,
            jobs: 2,
            max_keys: 10000,
            max_bytes: 268435456,
            top: 10,
            temp: std::env::var_os("DATA_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(std::env::temp_dir),
        };
        let mut args = std::env::args().skip(1);
        while let Some(key) = args.next() {
            if key == "--help" {
                println!("--input PATH (repeatable; directories supported) --format text|json --output PATH --jobs 1..16 --timeout-ms N --max-keys N --max-bytes N --top N --service NAME --level DEBUG|INFO|WARN|ERROR --from ISO --to ISO --keyword TEXT");
                std::process::exit(0);
            }
            let value = args.next().ok_or((2, format!("缺少参数值: {key}")))?;
            let number = || value.parse::<u64>().map_err(|_| (2, format!("{key} 必须为正整数")));
            match key.as_str() {
                "--input" => c.inputs.push(value.into()),
                "--format" => c.format = value,
                "--output" => c.output = Some(value.into()),
                "--timeout-ms" => c.timeout = number()?,
                "--jobs" => c.jobs = number()? as usize,
                "--max-keys" => c.max_keys = number()? as usize,
                "--max-bytes" => c.max_bytes = number()?,
                "--top" => c.top = number()? as usize,
                "--level" | "--from" | "--to" | "--service" | "--keyword" => {
                    if c.forwarded.iter().step_by(2).any(|k| k == &key) {
                        return Err((2, format!("重复筛选参数: {key}")));
                    }
                    c.forwarded.extend([key, value]);
                }
                _ => return Err((2, format!("未知参数: {key}"))),
            }
        }
        if c.inputs.is_empty()
            || !matches!(c.format.as_str(), "text" | "json")
            || !(1..=300000).contains(&c.timeout)
            || !(1..=16).contains(&c.jobs)
            || !(1..=100000).contains(&c.max_keys)
            || !(1..=1073741824).contains(&c.max_bytes)
            || !(1..=100).contains(&c.top)
        {
            return Err((2, "输入、格式或资源限制无效".into()));
        }
        c.forwarded.extend([
            "--max-keys".into(),
            c.max_keys.to_string(),
            "--max-bytes".into(),
            c.max_bytes.to_string(),
        ]);
        Ok(c)
    }
}
