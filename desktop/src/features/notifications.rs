use std::cell::RefCell;
use std::rc::Rc;
use std::sync::mpsc::{self, Receiver, Sender};
use std::time::{Duration, Instant};

use adw::prelude::*;
use gtk::glib;

use crate::ipc::Command;
use crate::model::{Device, NotificationSettings};

type Pending = (String, Receiver<Result<NotificationSettings, String>>);

#[derive(Default)]
struct State {
    selected: String,
    settings: Option<NotificationSettings>,
    pending: Option<Pending>,
    available: bool,
}

fn request(state: &mut State, tx: &Sender<Command>, change: Option<(String, bool)>) {
    if state.selected.is_empty() || state.pending.is_some() {
        return;
    }
    let (reply, receiver) = mpsc::channel();
    let id = state.selected.clone();
    if tx
        .send(Command::Notifications {
            device_id: id.clone(),
            change,
            reply,
        })
        .is_ok()
    {
        state.pending = Some((id, receiver));
    }
}

pub fn page(devices: impl Fn() -> Vec<Device> + 'static, tx: Sender<Command>) -> gtk::Box {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 12);
    for setter in [
        gtk::Widget::set_margin_top,
        gtk::Widget::set_margin_bottom,
        gtk::Widget::set_margin_start,
        gtk::Widget::set_margin_end,
    ] {
        setter(content.upcast_ref(), 16);
    }
    let selector = gtk::DropDown::from_strings(&[]);
    let device_ids = Rc::new(RefCell::new(Vec::<String>::new()));
    let status = gtk::Label::new(Some("Select a connected phone"));
    status.set_wrap(true);
    let search = gtk::SearchEntry::new();
    search.set_placeholder_text(Some("Search applications"));
    let rows = gtk::Box::new(gtk::Orientation::Vertical, 6);
    let scroll = gtk::ScrolledWindow::builder()
        .vexpand(true)
        .child(&rows)
        .build();
    content.append(&selector);
    content.append(&status);
    content.append(&search);
    content.append(&scroll);
    let state = Rc::new(RefCell::new(State::default()));
    let changed_state = state.clone();
    let changed_tx = tx.clone();
    let changed_ids = device_ids.clone();
    let selection_handler = selector.connect_selected_notify(move |selector| {
        let mut state = changed_state.borrow_mut();
        state.selected = changed_ids
            .borrow()
            .get(selector.selected() as usize)
            .cloned()
            .unwrap_or_default();
        state.available = false;
        state.settings = None;
        state.pending = None;
        request(&mut state, &changed_tx, None);
    });
    let weak_content = content.downgrade();
    let mut known_devices: Vec<Device> = Vec::new();
    let mut last_refresh = Instant::now();
    let mut rendered: Option<(String, Option<NotificationSettings>, String, bool)> = None;
    glib::timeout_add_local(Duration::from_millis(100), move || {
        let Some(content) = weak_content.upgrade() else {
            return glib::ControlFlow::Break;
        };
        if !content.is_mapped() {
            return glib::ControlFlow::Continue;
        }
        let current = devices();
        if current != known_devices {
            let selected = state.borrow().selected.clone();
            selector.block_signal(&selection_handler);
            *device_ids.borrow_mut() = current.iter().map(|d| d.device_id.clone()).collect();
            let labels: Vec<String> = current
                .iter()
                .map(|device| {
                    format!(
                        "{} ({})",
                        device.model,
                        device.device_id.chars().take(8).collect::<String>()
                    )
                })
                .collect();
            selector.set_model(Some(&gtk::StringList::new(
                &labels.iter().map(String::as_str).collect::<Vec<_>>(),
            )));
            let index = current
                .iter()
                .position(|d| d.device_id == selected)
                .unwrap_or(0);
            selector.set_selected(if current.is_empty() {
                gtk::INVALID_LIST_POSITION
            } else {
                index as u32
            });
            selector.unblock_signal(&selection_handler);
            let next_id = current
                .get(index)
                .map(|d| d.device_id.clone())
                .unwrap_or_default();
            if next_id != selected {
                let mut state = state.borrow_mut();
                state.selected = next_id;
                state.settings = None;
                state.pending = None;
                state.available = false;
                request(&mut state, &tx, None);
            }
            known_devices = current;
        }
        let mut state_ref = state.borrow_mut();
        let received = state_ref
            .pending
            .as_ref()
            .and_then(|(id, rx)| match rx.try_recv() {
                Ok(result) => Some((id.clone(), result)),
                Err(mpsc::TryRecvError::Disconnected) => {
                    Some((id.clone(), Err("Connection worker stopped".to_string())))
                }
                Err(mpsc::TryRecvError::Empty) => None,
            });
        if let Some((id, result)) = received {
            state_ref.pending = None;
            rendered = None;
            if id == state_ref.selected {
                match result {
                    Ok(settings) => {
                        state_ref.available = true;
                        status.set_label(if !settings.access_granted {
                            "Enable notification access in the Android app. Select applications below."
                        } else if !settings.listener_connected {
                            "Notification listener is not connected. Check notification access on the phone."
                        } else { "Only selected applications are forwarded." });
                        state_ref.settings = Some(settings);
                    }
                    Err(error) => {
                        status.set_label(&error);
                        // Keep confirmed values for rollback, but disable editing until refreshed.
                        state_ref.available = false;
                    }
                }
            }
            last_refresh = Instant::now();
        }
        if state_ref.selected.is_empty() {
            status.set_label("No connected phone. Connect Android to configure notifications.");
        } else if last_refresh.elapsed() >= Duration::from_secs(5) {
            request(&mut state_ref, &tx, None);
            last_refresh = Instant::now();
        }
        let key = (
            state_ref.selected.clone(),
            state_ref.settings.clone(),
            search.text().to_string(),
            state_ref.pending.is_some(),
        );
        if rendered.as_ref() != Some(&key) {
            while let Some(child) = rows.first_child() {
                rows.remove(&child);
            }
            rows.set_sensitive(state_ref.available && state_ref.pending.is_none());
            if let Some(settings) = &state_ref.settings {
                let query = search.text().to_lowercase();
                for app in &settings.apps {
                    if !format!("{} {}", app.label, app.package)
                        .to_lowercase()
                        .contains(&query)
                    {
                        continue;
                    }
                    let row = adw::ActionRow::builder()
                        .title(&app.label)
                        .subtitle(&app.package)
                        .build();
                    row.set_use_markup(false);
                    let toggle = gtk::Switch::builder()
                        .active(app.enabled)
                        .valign(gtk::Align::Center)
                        .build();
                    row.add_suffix(&toggle);
                    let state = state.clone();
                    let tx = tx.clone();
                    let package = app.package.clone();
                    toggle.connect_state_set(move |toggle, enabled| {
                        request(
                            &mut state.borrow_mut(),
                            &tx,
                            Some((package.clone(), enabled)),
                        );
                        toggle.set_sensitive(false);
                        glib::Propagation::Stop
                    });
                    rows.append(&row);
                }
            }
            rendered = Some(key);
        }
        glib::ControlFlow::Continue
    });
    content
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_parse_and_request_targets_selected_phone() {
        let settings: NotificationSettings = serde_json::from_value(serde_json::json!({
            "access_granted": true, "listener_connected": false,
            "apps": [{"package": "org.chat", "label": "Chat", "enabled": false}]
        }))
        .unwrap();
        let mut state = State {
            selected: "first".into(),
            settings: Some(settings.clone()),
            ..State::default()
        };
        let (tx, rx) = mpsc::channel();
        request(&mut state, &tx, Some(("org.chat".into(), true)));
        request(&mut state, &tx, Some(("org.chat".into(), false)));
        let Command::Notifications {
            device_id,
            change,
            reply,
        } = rx.try_recv().unwrap()
        else {
            panic!()
        };
        assert_eq!(device_id, "first");
        assert_eq!(change, Some(("org.chat".into(), true)));
        assert!(rx.try_recv().is_err());
        // The displayed value stays confirmed until the phone acknowledges the write.
        assert_eq!(state.settings, Some(settings));
        reply.send(Err("Disconnected".into())).unwrap();
        assert!(
            state
                .pending
                .as_ref()
                .unwrap()
                .1
                .try_recv()
                .unwrap()
                .is_err()
        );
    }

    #[test]
    fn switching_devices_isolates_pending_replies() {
        let (tx, rx) = mpsc::channel();
        let mut state = State {
            selected: "first".into(),
            ..State::default()
        };
        request(&mut state, &tx, None);
        let Command::Notifications { reply, .. } = rx.recv().unwrap() else {
            panic!()
        };
        state.selected = "second".into();
        state.pending = None;
        request(&mut state, &tx, None);
        assert!(reply.send(Err("Old response".into())).is_err());
        let Command::Notifications { device_id, .. } = rx.recv().unwrap() else {
            panic!()
        };
        assert_eq!(device_id, "second");
        assert_eq!(state.pending.as_ref().unwrap().0, "second");
        state.selected.clear();
        state.pending = None;
        request(&mut state, &tx, None);
        assert!(rx.try_recv().is_err());
    }
    #[test]
    #[ignore = "requires a display; run with xvfb-run"]
    fn dialog_rolls_back_failed_switch_and_handles_disconnect() {
        adw::init().unwrap();
        fn spin_until(mut check: impl FnMut() -> bool) {
            let deadline = Instant::now() + Duration::from_secs(3);
            loop {
                let context = glib::MainContext::default();
                while context.pending() {
                    context.iteration(false);
                }
                if check() {
                    return;
                }
                assert!(
                    Instant::now() < deadline,
                    "GUI did not reach expected state"
                );
                std::thread::sleep(Duration::from_millis(10));
            }
        }
        fn descendants(widget: &gtk::Widget) -> Vec<gtk::Widget> {
            let mut widgets = vec![widget.clone()];
            let mut child = widget.first_child();
            while let Some(current) = child {
                widgets.extend(descendants(&current));
                child = current.next_sibling();
            }
            widgets
        }
        fn switch(window: &gtk::Window) -> Option<gtk::Switch> {
            descendants(window.upcast_ref())
                .into_iter()
                .find_map(|w| w.downcast::<gtk::Switch>().ok())
        }
        let phones = Rc::new(RefCell::new(vec![Device {
            device_id: "first".into(),
            model: "Pixel".into(),
            host: "127.0.0.1".into(),
            port: 42,
            active: true,
        }]));
        let (tx, rx) = mpsc::channel();
        let parent = adw::ApplicationWindow::builder().build();
        let source = phones.clone();
        let test_window = gtk::Window::builder()
            .title("Notifications")
            .transient_for(&parent)
            .build();
        test_window.set_child(Some(&page(move || source.borrow().clone(), tx)));
        test_window.present();
        let window = gtk::Window::list_toplevels()
            .into_iter()
            .filter_map(|w| w.downcast::<gtk::Window>().ok())
            .find(|w| w.title().as_deref() == Some("Notifications"))
            .unwrap();
        let mut command = None;
        spin_until(|| {
            command = rx.try_recv().ok();
            command.is_some()
        });
        let Command::Notifications {
            device_id, reply, ..
        } = command.take().unwrap()
        else {
            panic!()
        };
        assert_eq!(device_id, "first");
        reply
            .send(Ok(NotificationSettings {
                access_granted: true,
                listener_connected: true,
                apps: vec![crate::model::NotificationApp {
                    package: "org.chat".into(),
                    label: "Chat".into(),
                    enabled: false,
                }],
            }))
            .unwrap();
        spin_until(|| switch(&window).is_some());
        switch(&window).unwrap().set_active(true);
        let Command::Notifications {
            device_id,
            change,
            reply,
        } = rx.try_recv().unwrap()
        else {
            panic!()
        };
        assert_eq!(device_id, "first");
        assert_eq!(change, Some(("org.chat".into(), true)));
        reply.send(Err("Save failed".into())).unwrap();
        spin_until(|| switch(&window).is_some_and(|s| !s.is_active() && !s.is_sensitive()));
        phones.borrow_mut().clear();
        spin_until(|| switch(&window).is_none());
        window.destroy();
        parent.destroy();
    }
}
