use std::cell::RefCell;
use std::rc::Rc;
use std::sync::mpsc;

use adw::prelude::*;

use crate::{ipc, ui};

pub const APP_ID: &str = "io.github.belzenn.AndroidLinuxBridge";

pub fn run() {
    if std::env::var_os("GSETTINGS_SCHEMA_DIR").is_none()
        && let Some(directory) = option_env!("GSETTINGS_SCHEMA_DIR")
    {
        unsafe { std::env::set_var("GSETTINGS_SCHEMA_DIR", directory) };
    }

    let application = adw::Application::builder().application_id(APP_ID).build();
    let worker = Rc::new(RefCell::new(None));
    let command_sender = Rc::new(RefCell::new(None));

    application.connect_activate({
        let worker = worker.clone();
        let command_sender = command_sender.clone();
        move |application| {
            if let Some(window) = application.active_window() {
                window.present();
                return;
            }
            let (command_tx, command_rx) = mpsc::channel();
            let (event_tx, event_rx) = mpsc::channel();
            *worker.borrow_mut() = Some(ipc::spawn(command_rx, event_tx));
            *command_sender.borrow_mut() = Some(command_tx.clone());
            ui::build(application, command_tx, event_rx);
        }
    });

    application.connect_shutdown(move |_| {
        if let Some(sender) = command_sender.borrow_mut().take() {
            let _ = sender.send(ipc::Command::Stop);
        }
        if let Some(handle) = worker.borrow_mut().take() {
            let _ = handle.join();
        }
    });

    application.run();
}
