use crate::config::Config;
use flate2::read::MultiGzDecoder;
use std::{
    collections::BTreeSet,
    fs::{self, File, OpenOptions},
    io::{self, Read},
    path::{Path, PathBuf},
};

fn linked(meta: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        meta.file_type().is_symlink() || meta.file_attributes() & 0x400 != 0
    }
    #[cfg(not(windows))]
    {
        meta.file_type().is_symlink()
    }
}
pub fn discover(inputs: &[PathBuf]) -> Vec<Result<PathBuf, (i32, String)>> {
    fn visit(
        path: &Path,
        found: &mut BTreeSet<PathBuf>,
        errors: &mut Vec<Result<PathBuf, (i32, String)>>,
        depth: usize,
    ) {
        if found.len() + errors.len() > 10000 {
            return;
        }
        let outcome = (|| -> io::Result<()> {
            let metadata = fs::symlink_metadata(path)?;
            if linked(&metadata) {
                return Err(io::Error::other("symbolic link or reparse point skipped"));
            }
            if metadata.is_dir() {
                if depth > 128 {
                    return Err(io::Error::other("directory depth exceeds 128"));
                }
                for entry in fs::read_dir(path)? {
                    if found.len() + errors.len() > 10000 {
                        break;
                    }
                    visit(&entry?.path(), found, errors, depth + 1);
                }
            } else if metadata.is_file() {
                found.insert(fs::canonicalize(path)?);
            } else {
                return Err(io::Error::other("not a regular file"));
            }
            if found.len() > 10000 {
                return Err(io::Error::other("input count exceeds 10000"));
            }
            Ok(())
        })();
        if let Err(error) = outcome {
            errors.push(Err((3, format!("{}: {error}", path.display()))));
        }
    }
    let mut found = BTreeSet::new();
    let mut errors = vec![];
    for path in inputs {
        visit(path, &mut found, &mut errors, 0);
    }
    let mut result: Vec<_> = found.into_iter().map(Ok).collect();
    result.extend(errors);
    result
}
pub struct Prepared {
    pub path: PathBuf,
    temporary: bool,
}
impl Drop for Prepared {
    fn drop(&mut self) {
        if self.temporary {
            let _ = fs::remove_file(&self.path);
        }
    }
}
pub fn prepare(path: &Path, c: &Config, index: usize) -> Result<Prepared, (i32, String)> {
    if path.extension().and_then(|s| s.to_str()) != Some("gz") {
        return Ok(Prepared {
            path: path.to_owned(),
            temporary: false,
        });
    }
    fs::create_dir_all(&c.temp).map_err(|e| (3, e.to_string()))?;
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let target = c
        .temp
        .join(format!("log-{}-{stamp}-{index}.tmp", std::process::id()));
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&target)
        .map_err(|e| (3, e.to_string()))?;
    let prepared = Prepared {
        path: target,
        temporary: true,
    };
    let result = (|| {
        let file = File::open(path)?;
        let mut decoder = MultiGzDecoder::new(file).take(c.max_bytes + 1);
        io::copy(&mut decoder, &mut output)
    })();
    drop(output);
    let bytes = result.map_err(|e| (3, format!("gzip 解码失败: {e}")))?;
    if bytes > c.max_bytes {
        return Err((3, "gzip 解压结果超过 --max-bytes".into()));
    }
    Ok(prepared)
}
