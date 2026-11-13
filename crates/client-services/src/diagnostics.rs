//! 诊断包：开发者选项里「导出诊断包」写出的 zip，以及「上传 MCP 日志快照」上传的 `sections`。
//!
//! 输入事件和性能记录按固定白名单逐行校验：一行只能是 `{"t_ms","kind","duration_ms"}` 这几个键，`kind` 只能是固定的事件种类，值只能是数字。任何多出来的键（例如误写进去的 `text`）、超出枚举的种类或不是 JSON 对象的行都整行丢弃并计数，绝不把输入内容带出本机。配置快照用 [`crate::preferences::Preferences::redacted_for_diagnostics`]，凭据一律换成 `"<redacted>"`。
//!
//! 来源文件由宿主给出绝对路径；文件不存在时这一类为空，不算失败。日志文件可能很大，只读最后 [`MAX_SOURCE_BYTES`] 字节，每类最多保留最近的若干行。

use crate::preferences::PreferencesStore;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
#[cfg(unix)]
use std::ffi::OsStr;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
#[cfg(unix)]
use std::os::unix::ffi::OsStrExt;
use std::path::Path;
use thiserror::Error;

/// 每个来源文件最多读的字节数（从文件末尾往前）。
pub const MAX_SOURCE_BYTES: u64 = 8 * 1024 * 1024;
/// 输入事件最多保留的行数。
pub const MAX_INPUT_EVENTS: usize = 5_000;
/// 性能记录最多保留的行数。
pub const MAX_PERFORMANCE_RECORDS: usize = 5_000;
/// 崩溃记录最多保留的条数。
pub const MAX_CRASH_LOGS: usize = 50;
/// 崩溃信息 `message` 的字节上限，与服务端相同。
pub const MAX_CRASH_MESSAGE_BYTES: usize = 2 * 1024;
/// 崩溃堆栈 `stack` 的字节上限，与服务端相同。
pub const MAX_CRASH_STACK_BYTES: usize = 16 * 1024;
/// 单行的字节上限；更长的行直接丢弃。
const MAX_LINE_BYTES: usize = 32 * 1024;

/// 诊断包固定事件种类；输入失败类别仅供本地导出，云端上传器另按服务契约过滤。
pub const EVENT_KINDS: [&str; 91] = [
    "key_down",
    "key_up",
    "candidate_shown",
    "candidate_selected",
    "commit",
    "backspace",
    "panel_open",
    "panel_close",
    "ime_start",
    "ime_finish",
    "input_failure_runtime_rejected",
    "input_failure_invalid_response",
    "input_failure_editor_rejected",
    "input_failure_jni_link",
    "voice_engine_local",
    "voice_engine_streaming",
    "voice_engine_platform",
    "voice_engine_http",
    "voice_activity_launch",
    "voice_local_model_check",
    "voice_local_runtime_check",
    "voice_local_recorder_open",
    "voice_local_native_start",
    "voice_local_capture_start",
    "voice_local_capture_read_failure",
    "voice_local_capture_start_failure",
    "voice_local_capture_exception",
    "voice_failure_permission",
    "voice_failure_audio",
    "voice_failure_model",
    "voice_failure_runtime_missing",
    "voice_failure_runtime",
    "voice_cancelled",
    "voice_empty",
    "voice_success",
    "voice_failure_security",
    "voice_failure_link",
    "voice_failure_null_pointer",
    "voice_failure_unexpected",
    "voice_failure_network",
    "voice_platform_server",
    "voice_platform_client",
    "voice_platform_busy",
    "voice_platform_unavailable",
    "voice_platform_other",
    "voice_failure_response",
    "voice_failure_timeout",
    "engine_session_create",
    "engine_session_ready",
    "ime_service_create",
    "input_view_create",
    "keyboard_first_draw",
    "engine_startup_preferences",
    "engine_startup_dictionary_sync",
    "engine_session_main",
    "engine_session_destroy",
    // Android 首帧布局诊断（#10389）：建会话用的偏好来源，以及会话到达前、会话建好时、实时偏好应用后各画的触屏布局，布局编进种类名里。
    "engine_session_live_preferences",
    "engine_session_factory_preferences",
    "engine_session_hinted_preferences",
    "touch_layout_first_drawn_twenty_six_key",
    "touch_layout_first_drawn_nine_key",
    "touch_layout_first_drawn_japanese_nine_key",
    "touch_layout_first_drawn_handwriting",
    "touch_layout_first_drawn_korean",
    "touch_layout_first_drawn_zhuyin",
    "touch_layout_first_drawn_stroke",
    "touch_layout_first_drawn_zhuyin_nine_key",
    "touch_layout_first_drawn_fourteen_key",
    "touch_layout_first_drawn_eighteen_key",
    "touch_layout_created_twenty_six_key",
    "touch_layout_created_nine_key",
    "touch_layout_created_japanese_nine_key",
    "touch_layout_created_handwriting",
    "touch_layout_created_korean",
    "touch_layout_created_zhuyin",
    "touch_layout_created_stroke",
    "touch_layout_created_zhuyin_nine_key",
    "touch_layout_created_fourteen_key",
    "touch_layout_created_eighteen_key",
    "touch_layout_live_twenty_six_key",
    "touch_layout_live_nine_key",
    "touch_layout_live_japanese_nine_key",
    "touch_layout_live_handwriting",
    "touch_layout_live_korean",
    "touch_layout_live_zhuyin",
    "touch_layout_live_stroke",
    "touch_layout_live_zhuyin_nine_key",
    "touch_layout_live_fourteen_key",
    "touch_layout_live_eighteen_key",
    // Android 按键路径拆段计时（#10457）：一次按键同步调引擎的耗时，和按键到 render() 改完视图的耗时。
    "engine_call",
    "candidate_rendered",
];

/// 要包含哪几类内容。缺省全部不包含。
#[derive(Clone, Copy, Debug, Default, Deserialize, Eq, PartialEq)]
#[serde(default, deny_unknown_fields)]
pub struct DiagnosticInclude {
    pub crash_logs: bool,
    pub performance_logs: bool,
    pub input_events: bool,
    pub config_snapshot: bool,
}

/// 各类日志的来源文件（绝对路径），没有时为空。
#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq)]
#[serde(default, deny_unknown_fields)]
pub struct DiagnosticSources {
    pub crash_logs: Option<String>,
    pub performance_logs: Option<String>,
    pub input_events: Option<String>,
}

/// 一次诊断包请求。`state_root` 是偏好目录（配置快照从这里读）。`destination` 是要写出的 zip 的绝对路径；为空时不写文件，而是把 `sections` 原样返回，供上传 MCP 快照。
#[derive(Clone, Debug, Deserialize, Eq, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct DiagnosticBundleRequest {
    pub state_root: String,
    #[serde(default)]
    pub include: DiagnosticInclude,
    #[serde(default)]
    pub sources: DiagnosticSources,
    #[serde(default)]
    pub destination: Option<String>,
}

/// 一类内容保留和丢弃的行数。
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize)]
pub struct SectionCount {
    pub kept: usize,
    /// 不符合白名单 schema 而丢弃的行。
    pub dropped: usize,
    /// 超过保留上限而略去的较早的行。
    pub truncated: usize,
}

/// 各类内容的计数；没有包含的类别为空。
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize)]
pub struct DiagnosticCounts {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub crash_logs: Option<SectionCount>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub performance_logs: Option<SectionCount>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_events: Option<SectionCount>,
    pub config_snapshot: bool,
}

/// 诊断包的结果。写了 zip 时 `path`、`bytes` 有值；没有 `destination` 时 `sections` 是上传接口要的对象（`crash_logs`、`perf_trace`、`input_events`、`config_snapshot`）。
#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct DiagnosticBundle {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bytes: Option<u64>,
    pub counts: DiagnosticCounts,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sections: Option<Map<String, Value>>,
}

#[derive(Debug, Error)]
pub enum DiagnosticsError {
    #[error("diagnostic bundle request is invalid")]
    Invalid,
    #[error("diagnostic source could not be read")]
    Source(#[source] std::io::Error),
    #[error("preferences could not be read for the configuration snapshot")]
    Preferences,
    #[error("diagnostic bundle could not be written")]
    Write(#[source] std::io::Error),
}

impl DiagnosticsError {
    /// 交给宿主的稳定错误码。
    pub fn code(&self) -> &'static str {
        match self {
            Self::Invalid => "diagnostics_invalid",
            Self::Source(_) => "diagnostics_source",
            Self::Preferences => "diagnostics_preferences",
            Self::Write(_) => "diagnostics_write",
        }
    }
}

/// 一类日志的校验结果。
struct Section {
    records: Vec<Value>,
    count: SectionCount,
}

/// 按请求生成诊断包。
pub fn build_bundle(
    request: &DiagnosticBundleRequest,
) -> Result<DiagnosticBundle, DiagnosticsError> {
    if !Path::new(&request.state_root).is_absolute() {
        return Err(DiagnosticsError::Invalid);
    }
    for source in [
        &request.sources.crash_logs,
        &request.sources.performance_logs,
        &request.sources.input_events,
    ]
    .into_iter()
    .flatten()
    {
        if !Path::new(source).is_absolute() {
            return Err(DiagnosticsError::Invalid);
        }
    }
    let destination = match &request.destination {
        Some(path) => {
            let path = Path::new(path);
            if !path.is_absolute() || path.file_name().is_none() || path.is_dir() {
                return Err(DiagnosticsError::Invalid);
            }
            Some(path)
        }
        None => None,
    };

    let crash = request
        .include
        .crash_logs
        .then(|| read_crash_logs(request.sources.crash_logs.as_deref()))
        .transpose()?;
    let performance = request
        .include
        .performance_logs
        .then(|| {
            read_section(
                request.sources.performance_logs.as_deref(),
                MAX_PERFORMANCE_RECORDS,
                performance_record,
            )
        })
        .transpose()?;
    let input = request
        .include
        .input_events
        .then(|| {
            read_section(
                request.sources.input_events.as_deref(),
                MAX_INPUT_EVENTS,
                input_event_record,
            )
        })
        .transpose()?;
    let config = if request.include.config_snapshot {
        let snapshot = PreferencesStore::new(&request.state_root)
            .load()
            .map_err(|_| DiagnosticsError::Preferences)?;
        Some(snapshot.preferences.redacted_for_diagnostics())
    } else {
        None
    };

    let counts = DiagnosticCounts {
        crash_logs: crash.as_ref().map(|section| section.count),
        performance_logs: performance.as_ref().map(|section| section.count),
        input_events: input.as_ref().map(|section| section.count),
        config_snapshot: config.is_some(),
    };

    match destination {
        Some(path) => {
            let bytes = write_zip(path, &counts, &crash, &performance, &input, &config)?;
            Ok(DiagnosticBundle {
                path: Some(path.to_string_lossy().into_owned()),
                bytes: Some(bytes),
                counts,
                sections: None,
            })
        }
        None => {
            let mut sections = Map::new();
            if let Some(section) = crash {
                sections.insert("crash_logs".into(), Value::Array(section.records));
            }
            if let Some(section) = performance {
                sections.insert("perf_trace".into(), Value::Array(section.records));
            }
            if let Some(section) = input {
                sections.insert("input_events".into(), Value::Array(section.records));
            }
            if let Some(config) = config {
                sections.insert("config_snapshot".into(), config);
            }
            Ok(DiagnosticBundle {
                path: None,
                bytes: None,
                counts,
                sections: Some(sections),
            })
        }
    }
}

fn sanitized_crash(at: &str, message: &str, stack: &str) -> Option<Value> {
    if message.trim().is_empty() {
        return None;
    }
    let message = exception_type(message.lines().next()?);
    let stack: Vec<String> = stack
        .lines()
        .map(|line| {
            let trimmed = line.trim_start();
            if ["at ", "... ", "#"]
                .iter()
                .any(|frame| trimmed.starts_with(frame))
            {
                line.to_owned()
            } else if let Some(cause) = trimmed.strip_prefix("Caused by: ") {
                format!("Caused by: {}", exception_type(cause))
            } else {
                exception_type(trimmed).to_owned()
            }
        })
        .collect();
    let stack = stack.join("\n");
    let stack = stack.chars().take(MAX_CRASH_STACK_BYTES).collect::<String>();
    let message: String = message.chars().take(MAX_CRASH_MESSAGE_BYTES).collect();
    let mut record = Map::new();
    record.insert("at".into(), at.into());
    record.insert("message".into(), message.into());
    record.insert("stack".into(), stack.into());
    Some(Value::Object(record))
}

/// `java.lang.IllegalStateException: 说明` 里冒号前的异常类型；没有冒号时是整行。
fn exception_type(line: &str) -> &str {
    line.split(':').next().unwrap_or(line).trim()
}

fn ndjson(records: &[Value]) -> Vec<u8> {
    let mut bytes = Vec::new();
    for record in records {
        bytes.extend_from_slice(record.to_string().as_bytes());
        bytes.push(b'\n');
    }
    bytes
}

fn write_zip(
    destination: &Path,
    counts: &DiagnosticCounts,
    crash: &Option<Section>,
    performance: &Option<Section>,
    input: &Option<Section>,
    config: &Option<Value>,
) -> Result<u64, DiagnosticsError> {
    let parent = destination.parent().ok_or(DiagnosticsError::Invalid)?;
    #[cfg(unix)]
    {
        let directory =
            crate::storage::open_private_directory(parent).map_err(DiagnosticsError::Write)?;
        let name = destination.file_name().ok_or(DiagnosticsError::Invalid)?;
        let (_, bytes) = crate::storage::write_private_file_at_with(&directory, name, |file| {
            write_zip_contents(file, counts, crash, performance, input, config)
                .map(|file| (file, ()))
        })
        .map_err(DiagnosticsError::Write)?;
        Ok(bytes)
    }
    #[cfg(not(unix))]
    {
        let temporary = tempfile::NamedTempFile::new_in(parent).map_err(DiagnosticsError::Write)?;
        let mut writer = zip::ZipWriter::new(temporary);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        let mut entry = |name: &str, bytes: &[u8]| -> Result<(), DiagnosticsError> {
            writer
                .start_file(name, options)
                .map_err(|error| DiagnosticsError::Write(std::io::Error::other(error)))?;
            writer.write_all(bytes).map_err(DiagnosticsError::Write)
        };
        let manifest = serde_json::json!({
            "format": "msime-diagnostics",
            "version": 1,
            "counts": counts,
        });
        entry("manifest.json", manifest.to_string().as_bytes())?;
        if let Some(section) = crash {
            entry("crash_logs.ndjson", &ndjson(&section.records))?;
        }
        if let Some(section) = performance {
            entry("performance_logs.ndjson", &ndjson(&section.records))?;
        }
        if let Some(section) = input {
            entry("input_events.ndjson", &ndjson(&section.records))?;
        }
        if let Some(config) = config {
            let text = serde_json::to_vec_pretty(config)
                .map_err(|error| DiagnosticsError::Write(std::io::Error::other(error)))?;
            entry("config_snapshot.json", &text)?;
        }
        let temporary = writer
            .finish()
            .map_err(|error| DiagnosticsError::Write(std::io::Error::other(error)))?;
        temporary
            .as_file()
            .sync_all()
            .map_err(DiagnosticsError::Write)?;
        let file = crate::storage::persist_replacing(temporary, destination)
            .map_err(|error| DiagnosticsError::Write(error.error))?;
        let bytes = file.metadata().map_err(DiagnosticsError::Write)?.len();
        Ok(bytes)
    }
}

#[cfg(unix)]
fn write_zip_contents(
    file: std::fs::File,
    counts: &DiagnosticCounts,
    crash: &Option<Section>,
    performance: &Option<Section>,
    input: &Option<Section>,
    config: &Option<Value>,
) -> Result<std::fs::File, std::io::Error> {
    let mut writer = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    let mut entry = |name: &str, bytes: &[u8]| -> Result<(), std::io::Error> {
        writer
            .start_file(name, options)
            .map_err(std::io::Error::other)?;
        writer.write_all(bytes)
    };
    let manifest = serde_json::json!({
        "format": "msime-diagnostics",
        "version": 1,
        "counts": counts,
    });
    entry("manifest.json", manifest.to_string().as_bytes())?;
    if let Some(section) = crash {
        entry("crash_logs.ndjson", &ndjson(&section.records))?;
    }
    if let Some(section) = performance {
        entry("performance_logs.ndjson", &ndjson(&section.records))?;
    }
    if let Some(section) = input {
        entry("input_events.ndjson", &ndjson(&section.records))?;
    }
    if let Some(config) = config {
        let text = serde_json::to_vec_pretty(config).map_err(std::io::Error::other)?;
        entry("config_snapshot.json", &text)?;
    }
    writer.finish().map_err(std::io::Error::other)
}

/// 把诊断包的 `sections` 按服务端规则重建并上传成 MCP 日志快照。
pub mod upload;

#[cfg(test)]
mod tests;
