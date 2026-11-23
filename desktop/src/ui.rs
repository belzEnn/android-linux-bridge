use std::cell::RefCell;
use std::collections::HashSet;
use std::rc::Rc;
use std::sync::mpsc::{Receiver, Sender};

use adw::prelude::*;
use gtk::gdk;
use gtk::glib;

use crate::app::APP_ID;
use crate::ipc::{Command, Event};
use crate::model::{Battery, Device, PairingRequest, TrustedDevice};

#[derive(Default)]
struct State {
    trusted: Vec<TrustedDevice>,
    devices: Vec<Device>,
    shown_pairings: HashSet<String>,
}

#[derive(Clone)]
struct Widgets {
    window: adw::ApplicationWindow,
    device_name: gtk::Label,
    status: gtk::Label,
    find_phone: gtk::Button,
    battery_card: gtk::Box,
    battery_level: gtk::Label,
    battery_progress: gtk::ProgressBar,
    charging: gtk::Label,
}

pub fn build(
    application: &adw::Application,
    command_tx: Sender<Command>,
    event_rx: Receiver<Event>,
) {
    install_css();
    let settings = gtk::gio::Settings::new(APP_ID);
    apply_color_scheme(&settings.string("color-scheme"));

    let window = adw::ApplicationWindow::builder()
        .application(application)
        .title("Android Linux Bridge")
        .default_width(900)
        .default_height(620)
        .build();
    let root = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    let sidebar = gtk::Box::new(gtk::Orientation::Vertical, 0);
    sidebar.set_hexpand(false);
    sidebar.add_css_class("sidebar");
    let sidebar_header = adw::HeaderBar::new();
    sidebar_header.set_show_end_title_buttons(false);
    let sidebar_title = gtk::Label::new(Some("Android Linux Bridge"));
    sidebar_title.add_css_class("heading");
    sidebar_header.set_title_widget(Some(&sidebar_title));
    let sidebar_toggle = gtk::ToggleButton::builder()
        .icon_name("sidebar-hide-symbolic")
        .tooltip_text("Collapse sidebar")
        .build();
    sidebar_header.pack_end(&sidebar_toggle);
    sidebar.append(&sidebar_header);
    let main = gtk::Box::new(gtk::Orientation::Vertical, 0);
    main.set_hexpand(true);
    let header = adw::HeaderBar::new();
    header.set_show_start_title_buttons(false);
    let page_title = adw::WindowTitle::new("Home", "");
    header.set_title_widget(Some(&page_title));
    main.append(&header);
    let stack = gtk::Stack::new();
    stack.set_transition_type(gtk::StackTransitionType::Crossfade);
    stack.set_vexpand(true);
    main.append(&stack);
    root.append(&sidebar);
    root.append(&gtk::Separator::new(gtk::Orientation::Vertical));
    root.append(&main);

    let state = Rc::new(RefCell::new(State::default()));
    let find_phone = gtk::Button::with_label("Find phone");
    find_phone.set_sensitive(false);
    find_phone.connect_clicked({
        let state = state.clone();
        let tx = command_tx.clone();
        move |_| {
            if let Some(device) = state.borrow().devices.iter().find(|device| device.active) {
                let command = if device.finding_phone {
                    Command::StopFindPhone {
                        device_id: device.device_id.clone(),
                    }
                } else {
                    Command::FindPhone {
                        device_id: device.device_id.clone(),
                    }
                };
                let _ = tx.send(command);
            }
        }
    });
    let (home, widgets) = build_home(&window, find_phone);
    stack.add_titled(&home, Some("home"), "Home");
    window.set_content(Some(&root));
    let notification_state = state.clone();
    let notifications = crate::features::notifications::page(
        move || notification_state.borrow().devices.clone(),
        command_tx.clone(),
    );
    stack.add_titled(&notifications, Some("notifications"), "Notifications");
    let devices = build_devices_page(&window, state.clone(), command_tx.clone());
    stack.add_titled(&devices, Some("devices"), "Devices");
    add_sidebar_navigation(&sidebar, &stack, &page_title);
    let spacer = gtk::Box::new(gtk::Orientation::Vertical, 0);
    spacer.set_vexpand(true);
    sidebar.append(&spacer);
    add_theme_selector(&sidebar, settings.clone());
    configure_sidebar_collapse(
        &sidebar,
        &sidebar_header,
        &sidebar_title,
        &sidebar_toggle,
        &settings,
    );
    stack.set_visible_child_name("home");

    glib::timeout_add_local(std::time::Duration::from_millis(100), move || {
        while let Ok(event) = event_rx.try_recv() {
            handle_event(&widgets, &state, &command_tx, event);
        }
        glib::ControlFlow::Continue
    });

    window.present();
}

fn build_home(
    window: &adw::ApplicationWindow,
    find_phone: gtk::Button,
) -> (gtk::ScrolledWindow, Widgets) {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 20);
    content.set_valign(gtk::Align::Start);

    let device_card = gtk::Box::new(gtk::Orientation::Vertical, 14);
    device_card.add_css_class("overview-card");
    let identity = gtk::Box::new(gtk::Orientation::Horizontal, 14);
    let phone = gtk::Image::from_icon_name("phone-symbolic");
    phone.set_pixel_size(24);
    phone.add_css_class("device-icon");
    phone.set_valign(gtk::Align::Center);
    identity.append(&phone);

    let labels = gtk::Box::new(gtk::Orientation::Vertical, 4);
    let device_name = gtk::Label::new(Some("Android device"));
    device_name.add_css_class("title-2");
    device_name.set_xalign(0.0);
    device_name.set_wrap(true);
    device_name.set_wrap_mode(gtk::pango::WrapMode::WordChar);
    labels.append(&device_name);

    let status = gtk::Label::new(None);
    status.set_xalign(0.0);
    status.set_wrap(true);
    set_status(&status, "Daemon offline", false);
    labels.append(&status);
    identity.append(&labels);
    device_card.append(&identity);

    device_card.append(&gtk::Separator::new(gtk::Orientation::Horizontal));
    find_phone.set_halign(gtk::Align::Start);
    device_card.append(&find_phone);
    content.append(&device_card);

    let battery_card = gtk::Box::new(gtk::Orientation::Vertical, 10);
    battery_card.add_css_class("overview-card");
    battery_card.set_visible(false);
    let battery_header = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    let battery_title = gtk::Label::new(Some("Battery"));
    battery_title.set_xalign(0.0);
    battery_title.set_hexpand(true);
    battery_title.add_css_class("heading");
    let battery_level = gtk::Label::new(None);
    battery_level.add_css_class("title-3");
    battery_header.append(&battery_title);
    battery_header.append(&battery_level);
    battery_card.append(&battery_header);

    let battery_progress = gtk::ProgressBar::new();
    battery_progress.add_css_class("battery-progress");
    battery_card.append(&battery_progress);
    let charging = gtk::Label::new(None);
    charging.set_xalign(0.0);
    charging.add_css_class("dim-label");
    battery_card.append(&charging);
    content.append(&battery_card);

    let home = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&page_clamp(&content))
        .build();
    let widgets = Widgets {
        window: window.clone(),
        device_name,
        status,
        find_phone,
        battery_card,
        battery_level,
        battery_progress,
        charging,
    };
    (home, widgets)
}

pub(crate) fn page_clamp(content: &gtk::Box) -> adw::Clamp {
    set_margins(content, 24);
    adw::Clamp::builder()
        .maximum_size(680)
        .tightening_threshold(540)
        .child(content)
        .build()
}

fn handle_event(
    widgets: &Widgets,
    state: &Rc<RefCell<State>>,
    command_tx: &Sender<Command>,
    event: Event,
) {
    match event {
        Event::Online => set_status(&widgets.status, "No device connected", false),
        Event::Offline => {
            widgets.find_phone.set_sensitive(false);
            update_find_phone_button(&widgets.find_phone, false);
            state.borrow_mut().devices.clear();
            widgets.device_name.set_label("Android device");
            widgets.battery_card.set_visible(false);
            set_status(&widgets.status, "Daemon offline", false);
        }
        Event::Devices(devices) => {
            widgets
                .find_phone
                .set_sensitive(devices.iter().any(|device| device.active));
            update_find_phone_button(
                &widgets.find_phone,
                devices
                    .iter()
                    .any(|device| device.active && device.finding_phone),
            );
            state.borrow_mut().devices = devices.clone();
            if let Some(device) = devices.iter().find(|device| device.active) {
                widgets.device_name.set_label(&device.model);
                let status = match device.transport.as_deref() {
                    Some("bluetooth") => "Connected via Bluetooth",
                    _ => "Connected",
                };
                set_status(&widgets.status, status, true);
            } else {
                widgets.device_name.set_label("Android device");
                widgets.battery_card.set_visible(false);
                set_status(&widgets.status, "No device connected", false);
            }
        }
        Event::Battery(battery) => update_battery(widgets, battery),
        Event::Pairings(requests) => show_new_pairings(widgets, state, command_tx, requests),
        Event::Trusted(devices) => state.borrow_mut().trusted = devices,
        Event::Error(message) => show_error(&widgets.window, &message),
    }
}

fn update_find_phone_button(button: &gtk::Button, ringing: bool) {
    button.set_label(if ringing {
        "Stop ringing"
    } else {
        "Find phone"
    });
    if ringing {
        button.add_css_class("destructive-action");
    } else {
        button.remove_css_class("destructive-action");
    }
}

fn set_status(label: &gtk::Label, text: &str, connected: bool) {
    label.set_label(text);
    label.remove_css_class("connected-status");
    label.remove_css_class("offline-status");
    label.add_css_class(if connected {
        "connected-status"
    } else {
        "offline-status"
    });
}

fn update_battery(widgets: &Widgets, battery: Option<Battery>) {
    let Some(battery) = battery else {
        widgets.battery_card.set_visible(false);
        return;
    };
    let low = battery.level <= 20;
    widgets.battery_card.set_visible(true);
    widgets
        .battery_level
        .set_label(&format!("{}%", battery.level));
    widgets
        .battery_progress
        .set_fraction(f64::from(battery.level) / 100.0);
    widgets.charging.set_label(if battery.charging {
        "Charging"
    } else {
        "Not charging"
    });
    for widget in [
        widgets.battery_level.upcast_ref::<gtk::Widget>(),
        widgets.battery_progress.upcast_ref::<gtk::Widget>(),
    ] {
        if low {
            widget.add_css_class("battery-low");
        } else {
            widget.remove_css_class("battery-low");
        }
    }
}

fn show_new_pairings(
    widgets: &Widgets,
    state: &Rc<RefCell<State>>,
    command_tx: &Sender<Command>,
    requests: Vec<PairingRequest>,
) {
    for request in requests {
        if !state.borrow_mut().shown_pairings.insert(request.id.clone()) {
            continue;
        }
        let (title, message) = if let Some(passkey) = &request.bluetooth_passkey {
            (
                "Enable Bluetooth backup?",
                format!(
                    "{} from {}\n\nConfirm that Android displays the same Bluetooth pairing code.\n\n{passkey}",
                    request.model, request.address
                ),
            )
        } else {
            (
                "Pair new device?",
                format!(
                    "{} from {}\n\nCompare this SHA-256 key fingerprint with your phone. Approve only if every group matches.\n\n{}",
                    request.model,
                    request.address,
                    request
                        .fingerprint
                        .as_bytes()
                        .chunks(4)
                        .map(|c| std::str::from_utf8(c).unwrap())
                        .collect::<Vec<_>>()
                        .join(" ")
                ),
            )
        };
        let dialog = adw::AlertDialog::new(Some(title), Some(&message));
        dialog.add_responses(&[("deny", "Deny"), ("allow", "Allow")]);
        dialog.set_default_response(Some("deny"));
        dialog.set_response_appearance("allow", adw::ResponseAppearance::Suggested);
        let sender = command_tx.clone();
        dialog.choose(
            Some(&widgets.window),
            gtk::gio::Cancellable::NONE,
            move |response| {
                let _ = sender.send(Command::RespondPairing {
                    id: request.id.clone(),
                    accepted: response == "allow",
                });
            },
        );
        break;
    }
}

fn add_sidebar_navigation(sidebar: &gtk::Box, stack: &gtk::Stack, title: &adw::WindowTitle) {
    let list = gtk::ListBox::new();
    list.add_css_class("navigation-sidebar");
    set_margins(&list, 8);
    for (name, label, icon) in [
        ("home", "Home", "go-home-symbolic"),
        (
            "notifications",
            "Notifications",
            "preferences-system-notifications-symbolic",
        ),
        ("devices", "Devices", "phone-symbolic"),
    ] {
        let row = gtk::ListBoxRow::new();
        row.set_widget_name(name);
        row.set_tooltip_text(Some(label));
        let content = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        content.add_css_class("sidebar-navigation-content");
        set_margins(&content, 10);
        if let Some(display) = gdk::Display::default()
            && gtk::IconTheme::for_display(&display).has_icon(icon)
        {
            content.append(&gtk::Image::from_icon_name(icon));
        }
        let label = gtk::Label::new(Some(label));
        label.add_css_class("sidebar-expanded-only");
        content.append(&label);
        row.set_child(Some(&content));
        list.append(&row);
    }
    let stack = stack.clone();
    let title = title.clone();
    list.connect_row_selected(move |_, row| {
        if let Some(row) = row {
            stack.set_visible_child_name(&row.widget_name());
            if let Some(child) = stack.visible_child() {
                title.set_title(stack.page(&child).title().as_deref().unwrap_or("Home"));
            }
        }
    });
    list.select_row(list.row_at_index(0).as_ref());
    sidebar.append(&list);
}

fn add_theme_selector(sidebar: &gtk::Box, settings: gtk::gio::Settings) {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 8);
    content.add_css_class("sidebar-expanded-only");
    set_margins(&content, 16);
    let label = gtk::Label::new(Some("Appearance"));
    label.set_halign(gtk::Align::Start);
    label.add_css_class("dim-label");
    content.append(&label);
    let choices = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    choices.add_css_class("linked");
    let mut first: Option<gtk::ToggleButton> = None;
    for (title, value) in [("System", "system"), ("Light", "light"), ("Dark", "dark")] {
        let choice = gtk::ToggleButton::with_label(title);
        choice.set_hexpand(true);
        if let Some(first) = &first {
            choice.set_group(Some(first));
        } else {
            first = Some(choice.clone());
        }
        choice.set_active(settings.string("color-scheme") == value);
        let settings_for_click = settings.clone();
        choice.connect_toggled(move |button| {
            if button.is_active() {
                let _ = settings_for_click.set_string("color-scheme", value);
                apply_color_scheme(value);
            }
        });
        let weak_choice = choice.downgrade();
        settings.connect_changed(Some("color-scheme"), move |settings, _| {
            if let Some(choice) = weak_choice.upgrade() {
                choice.set_active(settings.string("color-scheme") == value);
            }
        });
        choices.append(&choice);
    }
    content.append(&choices);
    sidebar.append(&content);
}

fn configure_sidebar_collapse(
    sidebar: &gtk::Box,
    header: &adw::HeaderBar,
    title: &gtk::Label,
    button: &gtk::ToggleButton,
    settings: &gtk::gio::Settings,
) {
    fn apply(
        sidebar: &gtk::Box,
        header: &adw::HeaderBar,
        title: &gtk::Label,
        button: &gtk::ToggleButton,
        collapsed: bool,
    ) {
        sidebar.set_width_request(if collapsed { 72 } else { 232 });
        let mut widgets = vec![sidebar.clone().upcast::<gtk::Widget>()];
        while let Some(widget) = widgets.pop() {
            let mut child = widget.first_child();
            while let Some(current) = child {
                if current.has_css_class("sidebar-expanded-only") {
                    current.set_visible(!collapsed);
                }
                if current.has_css_class("sidebar-navigation-content") {
                    current.set_halign(if collapsed {
                        gtk::Align::Center
                    } else {
                        gtk::Align::Fill
                    });
                }
                widgets.push(current.clone());
                child = current.next_sibling();
            }
        }

        header.remove(button);
        if collapsed {
            header.set_title_widget(Some(button));
        } else {
            title.set_visible(true);
            header.set_title_widget(Some(title));
            header.pack_end(button);
        }
    }

    let collapsed = settings.boolean("sidebar-collapsed");
    button.set_active(collapsed);
    button.set_icon_name(if collapsed {
        "sidebar-show-symbolic"
    } else {
        "sidebar-hide-symbolic"
    });
    button.set_tooltip_text(Some(if collapsed {
        "Expand sidebar"
    } else {
        "Collapse sidebar"
    }));
    apply(sidebar, header, title, button, collapsed);

    let sidebar = sidebar.clone();
    let header = header.clone();
    let title = title.clone();
    let settings = settings.clone();
    button.connect_toggled(move |button| {
        let collapsed = button.is_active();
        apply(&sidebar, &header, &title, button, collapsed);
        button.set_icon_name(if collapsed {
            "sidebar-show-symbolic"
        } else {
            "sidebar-hide-symbolic"
        });
        button.set_tooltip_text(Some(if collapsed {
            "Expand sidebar"
        } else {
            "Collapse sidebar"
        }));
        let _ = settings.set_boolean("sidebar-collapsed", collapsed);
    });
}

fn build_devices_page(
    parent: &adw::ApplicationWindow,
    state: Rc<RefCell<State>>,
    command_tx: Sender<Command>,
) -> gtk::ScrolledWindow {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 16);
    let heading = gtk::Label::new(Some("Trusted devices"));
    heading.add_css_class("title-2");
    heading.set_halign(gtk::Align::Start);
    content.append(&heading);
    let description = gtk::Label::new(Some(
        "Manage the phones allowed to connect to this computer.",
    ));
    description.set_wrap(true);
    description.set_halign(gtk::Align::Start);
    description.add_css_class("dim-label");
    content.append(&description);
    let list = gtk::ListBox::new();
    list.set_selection_mode(gtk::SelectionMode::None);
    list.add_css_class("boxed-list");
    content.append(&list);
    let reset = gtk::Button::with_label("Reset all trusted devices");
    reset.add_css_class("destructive-action");
    reset.set_halign(gtk::Align::Start);
    reset.set_sensitive(false);
    let sender = command_tx.clone();
    let weak_parent = parent.downgrade();
    reset.connect_clicked(move |_| {
        let Some(parent) = weak_parent.upgrade() else {
            return;
        };
        let confirm = adw::AlertDialog::new(
            Some("Reset all trusted devices?"),
            Some("Every Android device will need to be paired again."),
        );
        confirm.add_responses(&[("cancel", "Cancel"), ("reset", "Reset")]);
        confirm.set_response_appearance("reset", adw::ResponseAppearance::Destructive);
        let sender = sender.clone();
        confirm.choose(
            Some(&parent),
            gtk::gio::Cancellable::NONE,
            move |response| {
                if response == "reset" {
                    let _ = sender.send(Command::ResetTrusted);
                }
            },
        );
    });
    content.append(&reset);
    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&page_clamp(&content))
        .build();
    let weak_scroll = scroll.downgrade();
    let mut previous = None;
    glib::timeout_add_local(std::time::Duration::from_millis(250), move || {
        let Some(scroll) = weak_scroll.upgrade() else {
            return glib::ControlFlow::Break;
        };
        if !scroll.is_mapped() {
            return glib::ControlFlow::Continue;
        }
        let devices = state.borrow().trusted.clone();
        if previous.as_ref() != Some(&devices) {
            while let Some(child) = list.first_child() {
                list.remove(&child);
            }
            reset.set_sensitive(!devices.is_empty());
            if devices.is_empty() {
                list.append(
                    &adw::ActionRow::builder()
                        .title("No trusted devices")
                        .build(),
                );
            }
            for device in &devices {
                let row = adw::ActionRow::builder()
                    .title(&device.model)
                    .subtitle(&device.device_id)
                    .build();
                row.set_use_markup(false);
                let revoke = gtk::Button::with_label("Revoke");
                revoke.add_css_class("destructive-action");
                revoke.set_valign(gtk::Align::Center);
                let sender = command_tx.clone();
                let device_id = device.device_id.clone();
                revoke.connect_clicked(move |_| {
                    let _ = sender.send(Command::RevokeTrusted {
                        device_id: device_id.clone(),
                    });
                });
                row.add_suffix(&revoke);
                list.append(&row);
            }
            previous = Some(devices);
        }
        glib::ControlFlow::Continue
    });
    scroll
}

fn show_error(parent: &adw::ApplicationWindow, message: &str) {
    let dialog = adw::AlertDialog::new(Some("Daemon request failed"), Some(message));
    dialog.add_response("close", "Close");
    dialog.choose(Some(parent), gtk::gio::Cancellable::NONE, |_| {});
}

fn apply_color_scheme(value: &str) {
    let scheme = match value {
        "light" => adw::ColorScheme::ForceLight,
        "dark" => adw::ColorScheme::ForceDark,
        _ => adw::ColorScheme::Default,
    };
    adw::StyleManager::default().set_color_scheme(scheme);
}

fn install_css() {
    let provider = gtk::CssProvider::new();
    provider.load_from_string(include_str!("style.css"));
    if let Some(display) = gdk::Display::default() {
        gtk::style_context_add_provider_for_display(
            &display,
            &provider,
            gtk::STYLE_PROVIDER_PRIORITY_APPLICATION,
        );
    }
}

fn set_margins<W: IsA<gtk::Widget>>(widget: &W, margin: i32) {
    widget.set_margin_top(margin);
    widget.set_margin_bottom(margin);
    widget.set_margin_start(margin);
    widget.set_margin_end(margin);
}
