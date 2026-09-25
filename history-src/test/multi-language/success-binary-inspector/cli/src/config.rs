use std::path::PathBuf;
pub struct Config {
    pub inputs: Vec<String>,
    pub forwarded: Vec<String>,
    pub format: String,
    pub output: Option<PathBuf>,
    pub helper: PathBuf,
    pub timeout: u64,
    pub jobs: usize,
}
impl Config {
    pub fn parse() -> Result<Self, (i32, String)> {
        let exe = std::env::current_exe().map_err(|e| (3, e.to_string()))?;
        let default = exe
            .ancestors()
            .nth(4)
            .ok_or((3, "安装目录不完整".into()))?
            .join("native/build")
            .join(if cfg!(windows) {
                "binary-worker.exe"
            } else {
                "binary-worker"
            });
        let mut c = Self {
            inputs: vec![],
            forwarded: vec![],
            format: "text".into(),
            output: None,
            helper: std::env::var_os("NATIVE_HELPER").map(PathBuf::from).unwrap_or(default),
            timeout: 30000,
            jobs: 2,
        };
        let mut args = std::env::args().skip(1);
        let (mut min, mut max) = (i64::MIN, i64::MAX);
        while let Some(key) = args.next() {
            if key == "--help" {
                println!("--input FILE (repeatable) --type all|measurement|event --min N --max N --max-bytes N --jobs 1..16 --format text|json --output PATH --timeout-ms N");
                std::process::exit(0);
            }
            let value = args.next().ok_or((2, format!("缺少参数值: {key}")))?;
            match key.as_str() {
                "--input" => c.inputs.push(value),
                "--format" => c.format = value,
                "--output" => c.output = Some(value.into()),
                "--timeout-ms" => c.timeout = value.parse().map_err(|_| (2, "无效超时".into()))?,
                "--jobs" => c.jobs = value.parse().map_err(|_| (2, "无效并发数".into()))?,
                "--type" | "--min" | "--max" | "--max-bytes" => {
                    if c.forwarded.iter().step_by(2).any(|k| k == &key) {
                        return Err((2, "重复筛选参数".into()));
                    }
                    match key.as_str() {
                        "--type" => {
                            if !matches!(value.as_str(), "all" | "measurement" | "event") {
                                return Err((2, "无效记录类型".into()));
                            }
                        }
                        "--min" => min = value.parse().map_err(|_| (2, "无效下限".into()))?,
                        "--max" => max = value.parse().map_err(|_| (2, "无效上限".into()))?,
                        _ => {
                            let n: u64 = value.parse().map_err(|_| (2, "无效大小限制".into()))?;
                            if !(1..=1073741824).contains(&n) {
                                return Err((2, "大小限制超出范围".into()));
                            }
                        }
                    }
                    c.forwarded.extend([key, value]);
                }
                _ => return Err((2, format!("未知参数: {key}"))),
            }
        }
        if c.inputs.is_empty()
            || c.inputs.len() > 1000
            || !matches!(c.format.as_str(), "text" | "json")
            || !(1..=300000).contains(&c.timeout)
            || !(1..=16).contains(&c.jobs)
            || min > max
        {
            return Err((2, "输入、格式、范围或资源限制无效".into()));
        }
        Ok(c)
    }
}
