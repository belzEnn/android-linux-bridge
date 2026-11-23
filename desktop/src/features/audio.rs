use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::Duration;

use adw::prelude::*;
use gtk::{gio, glib};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::ipc::request_from_ui;

#[derive(Deserialize)]
struct Phone {
    path: String,
    label: String,
    on_pc: bool,
}

#[derive(Deserialize)]
struct AudioState {
    ready: bool,
    phones: Vec<Phone>,
}

pub fn card(window: &adw::ApplicationWindow) -> gtk::Box {
    let card = gtk::Box::new(gtk::Orientation::Vertical, 10);
    card.add_css_class("overview-card");
    let title = gtk::Label::new(Some("Phone audio"));
    title.set_xalign(0.0);
    title.add_css_class("heading");
    card.append(&title);

    let status = gtk::Label::new(Some("Checking Bluetooth…"));
    status.set_xalign(0.0);
    status.set_wrap(true);
    status.add_css_class("dim-label");
    card.append(&status);

    let controls = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let selector = gtk::DropDown::from_strings(&[]);
    selector.set_visible(false);
    selector.update_property(&[gtk::accessible::Property::Label("Bluetooth phone")]);
    let button = gtk::Button::with_label("Play on PC");
    button.set_sensitive(false);
    controls.append(&selector);
    controls.append(&button);
    card.append(&controls);

    let phones = Rc::new(RefCell::new(Vec::<Phone>::new()));
    let switching = Rc::new(Cell::new(false));
    let refresh = {
        let phones = phones.clone();
        let button = button.downgrade();
        let status = status.clone();
        let switching = switching.clone();
        move |selector: &gtk::DropDown| {
            let Some(button) = button.upgrade() else {
                return;
            };
            let phones = phones.borrow();
            let phone = phones.get(selector.selected() as usize);
            let busy = switching.get();
            selector.set_visible(!phones.is_empty());
            selector.set_sensitive(!busy && phones.len() > 1);
            button.set_sensitive(!busy && phone.is_some());
            if busy {
                button.set_label("Switching…");
            } else if let Some(phone) = phone {
                let (action, message) = if phone.on_pc {
                    ("Play on phone", "Bluetooth media is connected to this PC.")
                } else {
                    (
                        "Play on PC",
                        "Bluetooth media is disconnected from this PC.",
                    )
                };
                button.set_label(action);
                status.set_label(message);
            } else {
                button.set_label("Play on PC");
            }
        }
    };
    selector.connect_selected_notify(refresh.clone());

    button.connect_clicked({
        let phones = phones.clone();
        let switching = switching.clone();
        let selector = selector.downgrade();
        let window = window.downgrade();
        let refresh = refresh.clone();
        move |_| {
            let Some(selector) = selector.upgrade() else {
                return;
            };
            let Some(path) = phones
                .borrow()
                .get(selector.selected() as usize)
                .map(|phone| phone.path.clone())
            else {
                return;
            };
            switching.set(true);
            refresh(&selector);
            let (window, switching) = (window.clone(), switching.clone());
            glib::spawn_future_local(async move {
                let result =
                    request_from_ui::<Value>("audio.route.toggle", json!({"path": path})).await;
                switching.set(false);
                if let (Err(message), Some(window)) = (result, window.upgrade()) {
                    let dialog =
                        adw::AlertDialog::new(Some("Could not switch phone audio"), Some(&message));
                    dialog.add_response("close", "Close");
                    dialog.choose(Some(&window), gio::Cancellable::NONE, |_| {});
                }
            });
        }
    });

    let weak_card = card.downgrade();
    glib::spawn_future_local(async move {
        while weak_card.upgrade().is_some() {
            if !switching.get() {
                let (next, message) =
                    match request_from_ui::<AudioState>("audio.route.list", json!({})).await {
                        Ok(state) => {
                            let message = if state.ready {
                                "Pair your phone in Linux Bluetooth settings."
                            } else {
                                "Enable Bluetooth and make sure Linux can receive Bluetooth audio."
                            };
                            (state.phones, message.to_owned())
                        }
                        Err(error) => (Vec::new(), format!("Bluetooth unavailable: {error}")),
                    };
                if !switching.get() {
                    let selected = selector.selected() as usize;
                    let changed = phones
                        .borrow()
                        .iter()
                        .map(|phone| (&phone.path, &phone.label))
                        .ne(next.iter().map(|phone| (&phone.path, &phone.label)));
                    let index = phones
                        .borrow()
                        .get(selected)
                        .and_then(|current| {
                            next.iter().position(|phone| phone.path == current.path)
                        })
                        .unwrap_or(0);
                    let model = changed.then(|| {
                        let labels = next
                            .iter()
                            .map(|phone| phone.label.as_str())
                            .collect::<Vec<_>>();
                        gtk::StringList::new(&labels)
                    });
                    *phones.borrow_mut() = next;
                    if let Some(model) = model {
                        selector.set_model(Some(&model));
                        selector.set_selected(index as u32);
                    }
                    if phones.borrow().is_empty() {
                        status.set_label(&message);
                    }
                    refresh(&selector);
                }
            }
            glib::timeout_future(Duration::from_millis(500)).await;
        }
    });
    card
}
