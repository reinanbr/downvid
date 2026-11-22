//! JNI entry points for `com.downvid.downvid.browser.AdEngine`.

use crate::{Blocker, Verdict, TYPES};
use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::ptr;
use std::sync::RwLock;

static ENGINE: RwLock<Option<Blocker>> = RwLock::new(None);

fn direct<'a>(env: &JNIEnv, buf: &JByteBuffer) -> Option<&'a [u8]> {
    if buf.is_null() {
        return None;
    }
    let p = env.get_direct_buffer_address(buf).ok()?;
    let n = env.get_direct_buffer_capacity(buf).ok()?;
    // SAFETY: a direct buffer the caller keeps alive for this call.
    Some(unsafe { std::slice::from_raw_parts(p, n) })
}

fn string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn out(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(ptr::null_mut())
}

/// Loads the compiled engine (and the scriptlet/redirect resources).
#[no_mangle]
pub extern "system" fn Java_com_downvid_downvid_browser_AdEngine_nativeLoad(
    env: JNIEnv,
    _: JClass,
    dat: JByteBuffer,
    resources: JByteBuffer,
) -> jboolean {
    let Some(d) = direct(&env, &dat) else { return JNI_FALSE };
    match Blocker::load(d, direct(&env, &resources)) {
        Ok(b) => {
            *ENGINE.write().unwrap() = Some(b);
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    }
}

/// null = allow, "" = block, otherwise the `data:` URL to serve instead.
#[no_mangle]
pub extern "system" fn Java_com_downvid_downvid_browser_AdEngine_nativeMatch(
    mut env: JNIEnv,
    _: JClass,
    url: JString,
    source: JString,
    kind: jint,
) -> jstring {
    let (Some(u), Some(s)) = (string(&mut env, &url), string(&mut env, &source)) else { return ptr::null_mut() };
    let t = TYPES.get(kind as usize).copied().unwrap_or("other");
    let verdict = match ENGINE.read().unwrap().as_ref() {
        Some(b) => b.check(&u, &s, t),
        None => Verdict::Allow,
    };
    match verdict {
        Verdict::Allow => ptr::null_mut(),
        Verdict::Block => out(&mut env, ""),
        Verdict::Redirect(d) => out(&mut env, &d),
    }
}

/// JSON {css, script, generichide} for a page about to load.
#[no_mangle]
pub extern "system" fn Java_com_downvid_downvid_browser_AdEngine_nativeCosmetic(
    mut env: JNIEnv,
    _: JClass,
    url: JString,
) -> jstring {
    let Some(u) = string(&mut env, &url) else { return ptr::null_mut() };
    let r = match ENGINE.read().unwrap().as_ref() {
        Some(b) => b.cosmetic(&u),
        None => return ptr::null_mut(),
    };
    out(&mut env, &r)
}

/// CSS for generic rules matching the page's classes/ids.
#[no_mangle]
pub extern "system" fn Java_com_downvid_downvid_browser_AdEngine_nativeHidden(
    mut env: JNIEnv,
    _: JClass,
    url: JString,
    tokens: JString,
) -> jstring {
    let (Some(u), Some(t)) = (string(&mut env, &url), string(&mut env, &tokens)) else { return ptr::null_mut() };
    let r = match ENGINE.read().unwrap().as_ref() {
        Some(b) => b.hidden(&u, &t),
        None => return ptr::null_mut(),
    };
    out(&mut env, &r)
}
