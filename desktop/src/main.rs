mod app;
mod features;
mod ipc;
mod model;
mod ui;

use std::process::ExitCode;

use serde::Deserialize;
use serde_json::json;

const TOGGLE_PHONE_AUDIO: &str = "--toggle-phone-audio";

#[derive(Deserialize)]
struct AudioRoute {
    on_pc: bool,
}

fn main() -> ExitCode {
    let mut args = std::env::args_os().skip(1);
    if args.next().as_deref() != Some(TOGGLE_PHONE_AUDIO.as_ref()) {
        app::run();
        return ExitCode::SUCCESS;
    }

    let option = args.next();
    let address = args.next();
    let phone = match (
        option.as_deref(),
        address.as_ref().and_then(|value| value.to_str()),
    ) {
        (None, None) if address.is_none() => None,
        (Some(option), Some(address)) if option == "--phone" && args.next().is_none() => {
            Some(address)
        }
        _ => {
            eprintln!("Usage: android-linux-bridge-desktop {TOGGLE_PHONE_AUDIO} [--phone ADDRESS]");
            return ExitCode::FAILURE;
        }
    };
    let result = ipc::request_once::<AudioRoute>("audio.route.toggle", json!({"address": phone}));
    match result {
        Ok(route) => {
            println!(
                "{}",
                if route.on_pc {
                    "Phone audio switched to PC."
                } else {
                    "Phone audio returned to phone."
                }
            );
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}
