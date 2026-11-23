use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::Duration;

use adw::prelude::*;
use gtk::{gio, glib};

const BLUEZ: &str = "org.bluez";
const DEVICE: &str = "org.bluez.Device1";
const AUDIO_SOURCE: &str = "0000110a-0000-1000-8000-00805f9b34fb";
const AUDIO_SINK: &str = "0000110b-0000-1000-8000-00805f9b34fb";

#[derive(Clone, PartialEq, Eq)]
struct Phone {
    path: String,
    label: String,
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

    let window = window.downgrade();
    let weak_card = card.downgrade();
    glib::spawn_future_local(async move {
        let manager = match gio::DBusObjectManagerClient::new_for_bus_future(
            gio::BusType::System,
            gio::DBusObjectManagerClientFlags::DO_NOT_AUTO_START,
            BLUEZ,
            "/",
        )
        .await
        {
            Ok(manager) => manager,
            Err(error) => {
                status.set_label(&format!("Bluetooth unavailable: {}", error.message()));
                return;
            }
        };

        let phones = Rc::new(RefCell::new(Vec::<Phone>::new()));
        let switching = Rc::new(Cell::new(false));
        button.connect_clicked({
            let manager = manager.clone();
            let phones = phones.clone();
            let switching = switching.clone();
            let selector = selector.clone();
            move |button| {
                if switching.get() {
                    return;
                }
                let Some(phone) = phones.borrow().get(selector.selected() as usize).cloned() else {
                    return;
                };
                let play_on_pc = !receiving_audio(&manager, &phone.path);
                switching.set(true);
                button.set_label("Switching…");
                button.set_sensitive(false);
                selector.set_sensitive(false);
                let manager = manager.clone();
                let switching = switching.clone();
                let window = window.clone();
                glib::spawn_future_local(async move {
                    let result = set_route(&manager, &phone, play_on_pc).await;
                    switching.set(false);
                    if let (Err(message), Some(window)) = (result, window.upgrade()) {
                        let dialog = adw::AlertDialog::new(
                            Some("Could not switch phone audio"),
                            Some(&message),
                        );
                        dialog.add_response("close", "Close");
                        dialog.choose(Some(&window), gio::Cancellable::NONE, |_| {});
                    }
                });
            }
        });

        selector.connect_selected_notify({
            let manager = manager.clone();
            let phones = phones.clone();
            let button = button.downgrade();
            let status = status.clone();
            let switching = switching.clone();
            move |selector| {
                if let Some(button) = button.upgrade() {
                    update_button(
                        &manager,
                        &phones.borrow(),
                        selector,
                        &button,
                        &status,
                        switching.get(),
                    );
                }
            }
        });

        while weak_card.upgrade().is_some() {
            let (ready, available) = available_phones(&manager);
            if *phones.borrow() != available {
                let current = phones.borrow().get(selector.selected() as usize).cloned();
                let index = current
                    .and_then(|phone| available.iter().position(|item| item.path == phone.path))
                    .unwrap_or(0);
                let labels = available
                    .iter()
                    .map(|phone| phone.label.as_str())
                    .collect::<Vec<_>>();
                let model = gtk::StringList::new(&labels);
                *phones.borrow_mut() = available;
                selector.set_model(Some(&model));
                selector.set_selected(index as u32);
            }
            let phones = phones.borrow();
            selector.set_visible(!phones.is_empty());
            if phones.is_empty() {
                set_label(
                    &status,
                    if manager.name_owner().is_none() {
                        "Start the Linux Bluetooth service."
                    } else if ready {
                        "Pair your phone in Linux Bluetooth settings."
                    } else {
                        "Enable Bluetooth and make sure Linux can receive Bluetooth audio."
                    },
                );
            }
            update_button(
                &manager,
                &phones,
                &selector,
                &button,
                &status,
                switching.get(),
            );
            drop(phones);
            glib::timeout_future(Duration::from_millis(500)).await;
        }
    });
    card
}

fn available_phones(manager: &gio::DBusObjectManagerClient) -> (bool, Vec<Phone>) {
    let objects = manager.objects();
    let ready_adapters = objects
        .iter()
        .filter_map(|object| proxy(object, "org.bluez.Adapter1"))
        .filter(|adapter| {
            property::<bool>(adapter, "Powered") == Some(true) && supports(adapter, AUDIO_SINK)
        })
        .map(|adapter| adapter.object_path())
        .collect::<Vec<_>>();
    let mut phones = objects
        .into_iter()
        .filter_map(|object| proxy(&object, DEVICE))
        .filter_map(|device| {
            let adapter = property::<glib::variant::ObjectPath>(&device, "Adapter")?;
            if !ready_adapters
                .iter()
                .any(|path| path.as_str() == adapter.as_str())
                || property::<bool>(&device, "Paired") != Some(true)
                || !supports(&device, AUDIO_SOURCE)
            {
                return None;
            }
            let address = property::<String>(&device, "Address")?;
            let label = property::<String>(&device, "Alias")
                .map_or_else(|| address.clone(), |alias| format!("{alias} ({address})"));
            Some(Phone {
                path: device.object_path().into(),
                label,
            })
        })
        .collect::<Vec<_>>();
    phones.sort_by(|a, b| a.label.cmp(&b.label).then_with(|| a.path.cmp(&b.path)));
    (!ready_adapters.is_empty(), phones)
}

fn supports(proxy: &gio::DBusProxy, uuid: &str) -> bool {
    property::<Vec<String>>(proxy, "UUIDs")
        .is_some_and(|uuids| uuids.iter().any(|item| item.eq_ignore_ascii_case(uuid)))
}

fn property<T: glib::variant::FromVariant>(proxy: &gio::DBusProxy, name: &str) -> Option<T> {
    proxy.cached_property(name)?.get()
}

fn proxy(object: &gio::DBusObject, interface: &str) -> Option<gio::DBusProxy> {
    DBusObjectExt::interface(object, interface)?.downcast().ok()
}

fn receiving_audio(manager: &gio::DBusObjectManagerClient, path: &str) -> bool {
    manager.objects().iter().any(|object| {
        proxy(object, "org.bluez.MediaTransport1").is_some_and(|transport| {
            property::<String>(&transport, "UUID")
                .is_some_and(|uuid| uuid.eq_ignore_ascii_case(AUDIO_SINK))
                && property::<glib::variant::ObjectPath>(&transport, "Device")
                    .is_some_and(|device| device.as_str() == path)
        })
    })
}

fn set_label(label: &gtk::Label, text: &str) {
    if label.label() != text {
        label.set_label(text);
    }
}

fn update_button(
    manager: &gio::DBusObjectManagerClient,
    phones: &[Phone],
    selector: &gtk::DropDown,
    button: &gtk::Button,
    status: &gtk::Label,
    switching: bool,
) {
    let phone = phones.get(selector.selected() as usize);
    selector.set_sensitive(!switching && phones.len() > 1);
    button.set_sensitive(!switching && phone.is_some());
    if !switching {
        let on_pc = phone.is_some_and(|phone| receiving_audio(manager, &phone.path));
        let action = if on_pc { "Play on phone" } else { "Play on PC" };
        if button.label().as_deref() != Some(action) {
            button.set_label(action);
        }
        if phone.is_some() {
            set_label(
                status,
                if on_pc {
                    "Bluetooth media is connected to this PC."
                } else {
                    "Bluetooth media is disconnected from this PC."
                },
            );
        }
    }
}

async fn set_route(
    manager: &gio::DBusObjectManagerClient,
    phone: &Phone,
    play_on_pc: bool,
) -> Result<(), String> {
    let device = manager
        .object(&phone.path)
        .and_then(|object| proxy(&object, DEVICE))
        .ok_or("The Bluetooth phone is no longer available.")?;
    let method = if play_on_pc {
        "ConnectProfile"
    } else {
        "DisconnectProfile"
    };
    // BlueZ takes the remote profile UUID here. MediaTransport1 reports the local one.
    device
        .call_future(
            method,
            Some(&(AUDIO_SOURCE,).to_variant()),
            gio::DBusCallFlags::NONE,
            10_000,
        )
        .await
        .map_err(|error| error.message().to_string())?;

    for _ in 0..10 {
        if receiving_audio(manager, &phone.path) == play_on_pc {
            return Ok(());
        }
        glib::timeout_future(Duration::from_millis(200)).await;
    }
    Err("Bluetooth did not confirm the requested audio route.".into())
}
