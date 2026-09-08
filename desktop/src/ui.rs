use std::cell::{Cell, RefCell};
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

#[derive(Clone, Copy, Default)]
struct BatteryVisual {
    level: u8,
    charging: bool,
}

#[derive(Clone)]
struct Widgets {
    window: adw::ApplicationWindow,
    device_name: gtk::Label,
    status: gtk::Label,
    battery_card: gtk::Box,
    battery_level: gtk::Label,
    battery_progress: gtk::ProgressBar,
    battery_icon: gtk::DrawingArea,
    battery_visual: Rc<Cell<BatteryVisual>>,
    charging: gtk::Label,
    updated: gtk::Label,
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
        .default_width(1000)
        .default_height(680)
        .build();
    let root = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    let sidebar = gtk::Box::new(gtk::Orientation::Vertical, 0);
    sidebar.set_width_request(220);
    sidebar.set_hexpand(false);
    sidebar.add_css_class("sidebar");
    let sidebar_header = adw::HeaderBar::new();
    sidebar_header.set_show_end_title_buttons(false);
    let sidebar_title = gtk::Label::new(Some("Android Linux Bridge"));
    sidebar_title.add_css_class("sidebar-expanded-only");
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
    let page_title = gtk::Label::new(Some("Home"));
    header.set_title_widget(Some(&page_title));
    main.append(&header);
    let stack = gtk::Stack::new();
    stack.set_transition_type(gtk::StackTransitionType::Crossfade);
    stack.set_vexpand(true);
    main.append(&stack);
    root.append(&sidebar);
    root.append(&gtk::Separator::new(gtk::Orientation::Vertical));
    root.append(&main);

    let content = gtk::Box::new(gtk::Orientation::Vertical, 18);
    content.set_valign(gtk::Align::Center);
    content.set_halign(gtk::Align::Center);
    content.set_vexpand(true);
    content.set_width_request(452);

    let phone = build_phone_illustration();
    content.append(&phone);

    let device_name = gtk::Label::new(Some("Android device"));
    device_name.add_css_class("title-1");
    content.append(&device_name);

    let status = gtk::Label::new(Some("●  Daemon offline"));
    status.add_css_class("offline-status");
    content.append(&status);

    let battery_card = gtk::Box::new(gtk::Orientation::Vertical, 10);
    battery_card.add_css_class("battery-card");
    battery_card.set_margin_top(8);
    battery_card.set_visible(false);

    let battery_header = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    let battery_labels = gtk::Box::new(gtk::Orientation::Vertical, 2);
    battery_labels.set_hexpand(true);
    let battery_title = gtk::Label::new(Some("Battery charge"));
    battery_title.set_halign(gtk::Align::Start);
    battery_title.add_css_class("dim-label");
    let battery_level = gtk::Label::new(Some("0%"));
    battery_level.set_halign(gtk::Align::Start);
    battery_level.add_css_class("battery-level");
    battery_labels.append(&battery_title);
    battery_labels.append(&battery_level);
    let (battery_icon, battery_visual) = build_battery_icon();
    battery_header.append(&battery_labels);
    battery_header.append(&battery_icon);
    battery_card.append(&battery_header);

    let battery_progress = gtk::ProgressBar::new();
    battery_progress.add_css_class("battery-progress");
    battery_card.append(&battery_progress);

    let battery_footer = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    let charging = gtk::Label::new(Some("Not charging"));
    charging.set_halign(gtk::Align::Start);
    charging.set_hexpand(true);
    charging.add_css_class("dim-label");
    let updated = gtk::Label::new(Some("Updated just now"));
    updated.set_halign(gtk::Align::End);
    updated.add_css_class("dim-label");
    battery_footer.append(&charging);
    battery_footer.append(&updated);
    battery_card.append(&battery_footer);
    content.append(&battery_card);
    let home = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&content)
        .build();
    set_margins(&content, 24);
    stack.add_titled(&home, Some("home"), "Home");
    window.set_content(Some(&root));

    let state = Rc::new(RefCell::new(State::default()));
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

    let widgets = Widgets {
        window: window.clone(),
        device_name,
        status,
        battery_card,
        battery_level,
        battery_progress,
        battery_icon,
        battery_visual,
        charging,
        updated,
    };
    glib::timeout_add_local(std::time::Duration::from_millis(100), move || {
        while let Ok(event) = event_rx.try_recv() {
            handle_event(&widgets, &state, &command_tx, event);
        }
        glib::ControlFlow::Continue
    });

    window.present();
}

fn build_battery_icon() -> (gtk::DrawingArea, Rc<Cell<BatteryVisual>>) {
    let icon = gtk::DrawingArea::new();
    icon.set_content_width(58);
    icon.set_content_height(34);
    icon.set_halign(gtk::Align::Center);
    icon.set_valign(gtk::Align::Center);

    let visual = Rc::new(Cell::new(BatteryVisual::default()));
    let visual_for_draw = visual.clone();
    icon.set_draw_func(move |widget, context, width, height| {
        let visual = visual_for_draw.get();
        let scale = (f64::from(width) / 58.0).min(f64::from(height) / 34.0);
        let offset_x = (f64::from(width) - 58.0 * scale) / 2.0;
        let offset_y = (f64::from(height) - 34.0 * scale) / 2.0;
        let foreground = widget.color();

        let _ = context.save();
        context.translate(offset_x, offset_y);
        context.scale(scale, scale);

        // Slim horizontal shell with a small, centered terminal.
        rounded_rectangle(context, 1.5, 4.5, 50.0, 25.0, 6.0);
        context.set_source_rgba(
            f64::from(foreground.red()),
            f64::from(foreground.green()),
            f64::from(foreground.blue()),
            0.82,
        );
        context.set_line_width(2.0);
        let _ = context.stroke();

        rounded_rectangle(context, 53.0, 11.5, 4.0, 11.0, 2.0);
        context.set_source_rgba(
            f64::from(foreground.red()),
            f64::from(foreground.green()),
            f64::from(foreground.blue()),
            0.64,
        );
        let _ = context.fill();

        let fill_width = 42.0 * f64::from(visual.level.min(100)) / 100.0;
        if fill_width > 0.0 {
            rounded_rectangle(context, 5.5, 8.5, fill_width.max(3.0), 17.0, 3.0);
            if visual.charging {
                context.set_source_rgb(0.20, 0.78, 0.35);
            } else if visual.level <= 20 {
                context.set_source_rgb(0.88, 0.16, 0.20);
            } else {
                context.set_source_rgba(
                    f64::from(foreground.red()),
                    f64::from(foreground.green()),
                    f64::from(foreground.blue()),
                    0.88,
                );
            }
            let _ = context.fill();
        }

        if visual.charging {
            context.move_to(30.5, 8.0);
            context.line_to(22.0, 18.0);
            context.line_to(27.5, 18.0);
            context.line_to(24.5, 26.0);
            context.line_to(36.0, 15.0);
            context.line_to(30.0, 15.0);
            context.close_path();
            context.set_source_rgb(1.0, 1.0, 1.0);
            let _ = context.fill();
        }

        let _ = context.restore();
    });

    (icon, visual)
}

fn build_phone_illustration() -> gtk::DrawingArea {
    let phone = gtk::DrawingArea::new();
    phone.set_content_width(110);
    phone.set_content_height(190);
    phone.set_halign(gtk::Align::Center);

    phone.set_draw_func(|widget, context, width, height| {
        let foreground = widget.color();
        let scale = (f64::from(width) / 110.0).min(f64::from(height) / 190.0);
        let offset_x = (f64::from(width) - 110.0 * scale) / 2.0;
        let offset_y = (f64::from(height) - 190.0 * scale) / 2.0;

        let _ = context.save();
        context.translate(offset_x, offset_y);
        context.scale(scale, scale);

        // A subtle fill using the current theme foreground.
        rounded_rectangle(context, 7.0, 7.0, 96.0, 176.0, 22.0);
        context.set_source_rgba(
            f64::from(foreground.red()),
            f64::from(foreground.green()),
            f64::from(foreground.blue()),
            0.06,
        );
        let _ = context.fill();

        // Phone body.
        rounded_rectangle(context, 1.5, 1.5, 107.0, 187.0, 27.0);
        context.set_source_rgba(
            f64::from(foreground.red()),
            f64::from(foreground.green()),
            f64::from(foreground.blue()),
            0.9,
        );
        context.set_line_width(3.0);
        let _ = context.stroke();

        // Earpiece.
        rounded_rectangle(context, 39.0, 17.0, 32.0, 5.0, 2.5);
        context.set_source_rgba(
            f64::from(foreground.red()),
            f64::from(foreground.green()),
            f64::from(foreground.blue()),
            0.3,
        );
        let _ = context.fill();

        let _ = context.restore();
    });

    phone
}

fn rounded_rectangle(
    context: &gtk::cairo::Context,
    x: f64,
    y: f64,
    width: f64,
    height: f64,
    radius: f64,
) {
    use std::f64::consts::{FRAC_PI_2, PI};

    context.new_sub_path();
    context.arc(x + width - radius, y + radius, radius, -FRAC_PI_2, 0.0);
    context.arc(
        x + width - radius,
        y + height - radius,
        radius,
        0.0,
        FRAC_PI_2,
    );
    context.arc(x + radius, y + height - radius, radius, FRAC_PI_2, PI);
    context.arc(x + radius, y + radius, radius, PI, PI + FRAC_PI_2);
    context.close_path();
}

fn handle_event(
    widgets: &Widgets,
    state: &Rc<RefCell<State>>,
    command_tx: &Sender<Command>,
    event: Event,
) {
    match event {
        Event::Online => set_status(&widgets.status, "●  No device connected", false),
        Event::Offline => {
            state.borrow_mut().devices.clear();
            widgets.device_name.set_label("Android device");
            widgets.battery_card.set_visible(false);
            set_status(&widgets.status, "●  Daemon offline", false);
        }
        Event::Devices(devices) => {
            state.borrow_mut().devices = devices.clone();
            if let Some(device) = devices.iter().find(|device| device.active) {
                widgets.device_name.set_label(&device.model);
                set_status(&widgets.status, "●  Connected", true);
            } else {
                widgets.device_name.set_label("Android device");
                widgets.battery_card.set_visible(false);
                set_status(&widgets.status, "●  No device connected", false);
            }
        }
        Event::Battery(battery) => update_battery(widgets, battery),
        Event::Pairings(requests) => show_new_pairings(widgets, state, command_tx, requests),
        Event::Trusted(devices) => state.borrow_mut().trusted = devices,
        Event::Error(message) => show_error(&widgets.window, &message),
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
    widgets.updated.set_label("Updated just now");
    widgets.battery_visual.set(BatteryVisual {
        level: battery.level,
        charging: battery.charging,
    });
    widgets.battery_icon.queue_draw();
    widgets.battery_level.remove_css_class("battery-low");
    if low {
        widgets.battery_level.add_css_class("battery-low");
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
        let dialog = adw::AlertDialog::new(
            Some("Pair new device?"),
            Some(&format!("{} from {}", request.model, request.address)),
        );
        dialog.add_responses(&[("deny", "Deny"), ("allow", "Allow")]);
        dialog.set_default_response(Some("allow"));
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

fn add_sidebar_navigation(sidebar: &gtk::Box, stack: &gtk::Stack, title: &gtk::Label) {
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
                title.set_label(stack.page(&child).title().as_deref().unwrap_or("Home"));
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
        sidebar.set_width_request(if collapsed { 72 } else { 220 });
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
    set_margins(&content, 24);
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
        .child(&content)
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
    provider.load_from_string(
        ".battery-card { background: alpha(@window_fg_color, 0.07); border: 1px solid alpha(@window_fg_color, 0.12); border-radius: 14px; padding: 22px 26px; }
         .battery-level { font-size: 36px; font-weight: 500; }
         .battery-progress trough { min-height: 8px; border-radius: 8px; background: alpha(@window_fg_color, 0.22); }
         .battery-progress progress { min-height: 8px; border-radius: 8px; background: @accent_bg_color; }
         .battery-low { color: #e01b24; }
         .connected-status { color: @success_color; }
         .offline-status { color: inherit; opacity: 0.65; }",
    );
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
