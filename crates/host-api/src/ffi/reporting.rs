//! 面向原生宿主的公告和检查更新：`msime_client_services::notices` 提供的公告源和 Markdown 渲染，以及 `msime_client_services::update_check`。
//!
//! Part of the C ABI; see the parent module for what these shims guarantee. Every request is a small JSON document so a host builds it with the JSON library it already has, and a field added later does not change a signature.

use super::with_bounded_bytes;
use crate::*;
use msime_client_services::account::BackendAccountClient;
use msime_client_services::notices::{fetch_notices, markdown_to_html, NoticeChannel, NoticeStore};
use msime_client_services::update_check::background::{pending_update, run_background_check};
use msime_client_services::update_check::{check_for_update, UpdateCheckRequest};
use std::time::SystemTime;

const MAX_REQUEST_BYTES: usize = 16 * 1024;
const MAX_MARKDOWN_BYTES: usize = 256 * 1024;

unsafe fn document<T: serde::de::DeserializeOwned>(
    pointer: *const u8,
    length: usize,
    maximum: usize,
) -> Result<T, String> {
    // SAFETY: forwarded from the documented caller contract.
    unsafe {
        with_bounded_bytes(
            pointer,
            length,
            maximum,
            "invalid request buffer",
            |bytes| {
                serde_json::from_slice(bytes).map_err(|_| "invalid request document".to_owned())
            },
        )
    }
}

fn absolute_directory(value: &str) -> Result<&Path, String> {
    let path = Path::new(value);
    if !path.is_absolute() {
        return Err("directory must be absolute".into());
    }
    Ok(path)
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct NoticesRequest {
    directory: String,
    platform: String,
    #[serde(default = "app_channel")]
    channel: NoticeChannel,
}

fn app_channel() -> NoticeChannel {
    NoticeChannel::App
}

/// The live notices for this host that the user has not dismissed, newest first: `{items:[{id,title,body,html,targets,channels,published_at}]}`, where `html` is the body rendered by `msime_client_markdown_to_html`. Request: `{directory, platform, channel?}` (`channel` defaults to `app`). Uses the copy cached under `directory` when the feed was requested less than a minute ago, and that copy when the request fails. Blocks on the network: call off the input thread, when the settings window or app home opens.
/// # Safety
/// `request` points to `length` readable UTF-8 JSON bytes. Null is rejected.
#[no_mangle]
pub unsafe extern "C" fn msime_client_notices(request: *const u8, length: usize) -> *mut c_char {
    response(|| {
        // SAFETY: guaranteed by the documented caller contract.
        let request: NoticesRequest = unsafe { document(request, length, MAX_REQUEST_BYTES)? };
        let store = NoticeStore::new(absolute_directory(&request.directory)?);
        let items = store
            .current(
                request.channel,
                &request.platform,
                SystemTime::now(),
                || {
                    fetch_notices(
                        &BackendAccountClient::new()?,
                        request.channel,
                        &request.platform,
                    )
                },
            )
            .map_err(|error| error.to_string())?;
        let items: Vec<Value> = items
            .into_iter()
            .map(|notice| {
                json!({
                    "id": notice.id,
                    "title": notice.title,
                    "html": markdown_to_html(&notice.body),
                    "body": notice.body,
                    "targets": notice.targets,
                    "channels": notice.channels,
                    "published_at": notice.published_at,
                })
            })
            .collect();
        Ok(json!({ "items": items }))
    })
}

/// 把宿主所在平台在请求通道里最新的构建与当前运行的构建比较，结果为 `{status:"available"|"current", update:{version:{display,parts}, release_url, installer_name, installer_sha256, signed, channel, build_run}}`；该平台还没有发布时为 `{status:"none"}`。请求为 `{platform, current_version, edition?, arch?, channel?}`，`channel` 是 `stable`（缺省）、`beta` 或 `nightly`。会在网络上阻塞，每个请求最多十秒，不要在 UI 线程或输入线程上调用。
/// # Safety
/// `request` 指向 `length` 个可读的 UTF-8 JSON 字节。传入空指针会被拒绝。
#[no_mangle]
pub unsafe extern "C" fn msime_client_update_check(
    request: *const u8,
    length: usize,
) -> *mut c_char {
    response(|| {
        // SAFETY: 由文档写明的调用方契约保证。
        let request: UpdateCheckRequest = unsafe { document(request, length, MAX_REQUEST_BYTES)? };
        let check = check_for_update(&request).map_err(|error| error.to_string())?;
        serde_json::to_value(check).map_err(|error| error.to_string())
    })
}

/// 后台检查和读缓存的请求：`msime_client_update_check` 的请求加上宿主的状态目录 `directory`（绝对路径，记录 `update-check.json` 放在这里）。
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct BackgroundUpdateRequest {
    directory: String,
    platform: String,
    current_version: String,
    #[serde(default)]
    edition: Option<String>,
    #[serde(default)]
    arch: Option<String>,
    #[serde(default)]
    channel: Option<String>,
}

impl BackgroundUpdateRequest {
    fn split(self) -> (String, UpdateCheckRequest) {
        (
            self.directory,
            UpdateCheckRequest {
                platform: self.platform,
                current_version: self.current_version,
                edition: self.edition,
                arch: self.arch,
                channel: self.channel,
            },
        )
    }
}

/// 后台检查一次更新（`msime_client_services::update_check::background::run_background_check`）：距上次成功检查不满一天、距上次失败不满六小时、另一个进程正在检查、开发构建或设置了 `MSIME_DISABLE_UPDATE_CHECK` 时不联网。请求为 `{directory, platform, current_version, edition?, arch?, channel?}`，`directory` 是宿主状态目录的绝对路径，同一宿主的读缓存请求要用同一个目录。结果为 `{status:"disabled"|"not_due"|"unavailable"|"checked", notify: update|null}`，`notify` 的形状同 `msime_client_update_check` 的 `update`，是这次该提醒、而且已经记成提醒过的构建。没到时间时只读一个小文件，可以每小时调用；到时间时会在网络上阻塞，不要在 UI 线程或输入线程上调用。
/// # Safety
/// `request` 指向 `length` 个可读的 UTF-8 JSON 字节。传入空指针会被拒绝。
#[no_mangle]
pub unsafe extern "C" fn msime_client_update_background_check(
    request: *const u8,
    length: usize,
) -> *mut c_char {
    response(|| {
        // SAFETY: 由文档写明的调用方契约保证。
        let request: BackgroundUpdateRequest =
            unsafe { document(request, length, MAX_REQUEST_BYTES)? };
        let (directory, request) = request.split();
        let outcome = run_background_check(absolute_directory(&directory)?, &request)
            .map_err(|error| error.to_string())?;
        serde_json::to_value(outcome).map_err(|error| error.to_string())
    })
}

/// 后台检查已经找到、仍比当前运行的构建新的版本，关于页打开时先显示它：`{status:"available", update}`，没有时为 null。请求与 `msime_client_update_background_check` 相同；记录是按别的通道检查的时也为 null。只读记录，不联网、不拿锁。
/// # Safety
/// `request` 指向 `length` 个可读的 UTF-8 JSON 字节。传入空指针会被拒绝。
#[no_mangle]
pub unsafe extern "C" fn msime_client_update_pending(
    request: *const u8,
    length: usize,
) -> *mut c_char {
    response(|| {
        // SAFETY: 由文档写明的调用方契约保证。
        let request: BackgroundUpdateRequest =
            unsafe { document(request, length, MAX_REQUEST_BYTES)? };
        let (directory, request) = request.split();
        let pending = pending_update(absolute_directory(&directory)?, &request)
            .map_err(|error| error.to_string())?;
        serde_json::to_value(pending).map_err(|error| error.to_string())
    })
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct DismissRequest {
    directory: String,
    id: String,
}

/// Remembers under `directory` that the user dismissed notice `id` (`{directory, id}`), so `msime_client_notices` no longer lists it.
/// # Safety
/// `request` points to `length` readable UTF-8 JSON bytes. Null is rejected.
#[no_mangle]
pub unsafe extern "C" fn msime_client_notice_dismiss(
    request: *const u8,
    length: usize,
) -> *mut c_char {
    response(|| {
        // SAFETY: guaranteed by the documented caller contract.
        let request: DismissRequest = unsafe { document(request, length, MAX_REQUEST_BYTES)? };
        NoticeStore::new(absolute_directory(&request.directory)?)
            .dismiss(&request.id)
            .map(|()| Value::Bool(true))
            .map_err(|error| error.to_string())
    })
}

/// Renders simple Markdown (a notice body) to HTML for a rich-text view: raw HTML in the source is escaped, only http, https and mailto links are kept, and images become links instead of being loaded. Value is the HTML string.
/// # Safety
/// `text` points to `length` readable UTF-8 bytes. Null is rejected.
#[no_mangle]
pub unsafe extern "C" fn msime_client_markdown_to_html(
    text: *const u8,
    length: usize,
) -> *mut c_char {
    response(|| {
        // SAFETY: guaranteed by the documented caller contract.
        unsafe {
            with_bounded_bytes(
                text,
                length,
                MAX_MARKDOWN_BYTES,
                "invalid text buffer",
                |bytes| {
                    let text = std::str::from_utf8(bytes).map_err(|_| "invalid text encoding")?;
                    Ok(Value::String(markdown_to_html(text)))
                },
            )
        }
    })
}
