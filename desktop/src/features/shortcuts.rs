use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, glib};
use serde::Deserialize;
use serde_json::json;

use crate::ipc::request_from_ui;

const AUDIO_COMMAND: &str = "android-linux-bridge-daemon --toggle-phone-audio";

pub fn page(window: &adw::ApplicationWindow) -> gtk::ScrolledWindow {
    let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default();
    let session = std::env::var("HYPRLAND_INSTANCE_SIGNATURE").ok();
    let instructions = desktop
        .split(':')
        .find_map(|name| match name.to_ascii_lowercase().as_str() {
            "gnome" => Some("Add the command in GNOME Settings → Keyboard → Custom Shortcuts."),
            "kde" | "plasma" => {
                Some("Add the command in Plasma System Settings → Keyboard → Shortcuts.")
            }
            "xfce" => Some("Add the command in Xfce Settings → Keyboard → Application Shortcuts."),
            "sway" => Some("Add a bindsym … exec binding for the command in your Sway config."),
            _ => None,
        })
        .unwrap_or("Assign the command to a global shortcut in your desktop settings.");
    let row = adw::ActionRow::builder()
        .title("Toggle phone audio")
        .subtitle("Switch Bluetooth media between your phone and this PC.")
        .build();
    row.set_subtitle_lines(3);
    let command = gtk::Label::new(Some(AUDIO_COMMAND));
    command.set_ellipsize(gtk::pango::EllipsizeMode::Middle);
    command.set_width_chars(28);
    command.set_max_width_chars(38);
    command.set_tooltip_text(Some(AUDIO_COMMAND));
    glib::spawn_future_local({
        let command = command.downgrade();
        async move {
            if let Ok(info) =
                request_from_ui::<AudioCommand>("keybinds.audio-command", json!({})).await
                && let Some(label) = command.upgrade()
            {
                label.set_text(&info.command);
                label.set_tooltip_text(Some(&info.command));
            }
        }
    });
    if let Some(session) = session {
        let button = gtk::MenuButton::builder()
            .child(&command)
            .always_show_arrow(true)
            .valign(gtk::Align::Center)
            .popover(&hyprland_options(window, session, &command))
            .build();
        row.add_suffix(&button);
    } else {
        let button = gtk::Button::builder().child(&command).build();
        button.set_valign(gtk::Align::Center);
        button.connect_clicked(move |button| button.clipboard().set_text(&command.text()));
        row.add_suffix(&button);
        row.set_subtitle(&format!(
            "Switch Bluetooth media between your phone and this PC. {instructions}"
        ));
    }

    let group = adw::PreferencesGroup::new();
    group.add(&row);
    group.set_margin_top(24);
    group.set_margin_bottom(24);
    group.set_margin_start(24);
    group.set_margin_end(24);
    let clamp = adw::Clamp::builder()
        .maximum_size(760)
        .child(&group)
        .build();
    gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&clamp)
        .build()
}

#[derive(Clone)]
struct Shortcut {
    key: String,
    keycode: u32,
    modifiers: u32,
    lua: bool,
}

#[derive(Deserialize)]
struct Binding {
    key: String,
    modifiers: u32,
    lua: bool,
    needs_rebind: bool,
}

impl Binding {
    fn label(&self) -> String {
        let mut keys = modifier_labels(self.modifiers);
        keys.push(&self.key);
        keys.join(" + ")
    }

    fn shortcut(&self) -> Shortcut {
        Shortcut {
            key: self.key.clone(),
            keycode: 0,
            modifiers: self.modifiers,
            lua: self.lua,
        }
    }
}

#[derive(Deserialize)]
struct BindingState {
    bindings: Vec<Binding>,
    command: String,
}

#[derive(Deserialize)]
struct AudioCommand {
    command: String,
}

fn hyprland_options(
    window: &adw::ApplicationWindow,
    session: String,
    command: &gtk::Label,
) -> gtk::Popover {
    let popover = gtk::Popover::new();
    let content = gtk::Box::new(gtk::Orientation::Vertical, 8);
    content.set_width_request(310);
    let field = gtk::Entry::builder()
        .editable(false)
        .placeholder_text("Press a key combination")
        .build();
    field.update_property(&[gtk::accessible::Property::Label("Key combination")]);
    let status = gtk::Label::new(None);
    status.set_wrap(true);
    status.set_xalign(0.0);
    status.add_css_class("dim-label");
    let choices = gtk::DropDown::from_strings(&[]);
    choices.set_visible(false);
    choices.update_property(&[gtk::accessible::Property::Label(
        "Existing phone audio bindings",
    )]);
    let existing = Rc::new(RefCell::new(Vec::<Binding>::new()));

    let buttons = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    let apply = gtk::Button::with_label("Apply");
    let remove = gtk::Button::with_label("Remove");
    let copy = gtk::Button::with_label("Copy command");
    let copy_bind = gtk::Button::with_label("Copy bind");
    copy_bind.set_sensitive(false);
    copy.connect_clicked({
        let command = command.downgrade();
        move |button| {
            if let Some(command) = command.upgrade() {
                button.clipboard().set_text(&command.text());
            }
        }
    });
    buttons.append(&apply);
    buttons.append(&remove);
    buttons.append(&copy);
    buttons.append(&copy_bind);
    content.append(&field);
    content.append(&choices);
    content.append(&buttons);
    content.append(&status);
    popover.set_child(Some(&content));

    let captured = Rc::new(RefCell::new(None::<Shortcut>));
    copy_bind.connect_clicked({
        let captured = captured.clone();
        let command = command.downgrade();
        move |button| {
            if let (Some(shortcut), Some(command)) = (captured.borrow().as_ref(), command.upgrade())
            {
                let command = command.text();
                let modifiers = modifier_labels(shortcut.modifiers);
                let config = if shortcut.lua {
                    let chord = format!(
                        "{} + {}",
                        modifiers.join(" + ").to_ascii_uppercase(),
                        shortcut.key
                    );
                    let command = serde_json::to_string(command.as_str()).unwrap();
                    format!("hl.bind(\"{chord}\", hl.dsp.exec_cmd({command}))")
                } else {
                    let modifiers = modifiers.join(" ").to_ascii_uppercase();
                    format!("bind = {modifiers}, {}, exec, {command}", shortcut.key)
                };
                button.clipboard().set_text(&config);
            }
        }
    });
    choices.connect_selected_notify({
        let existing = existing.clone();
        let captured = captured.clone();
        move |choices| {
            *captured.borrow_mut() = existing
                .borrow()
                .get(choices.selected() as usize)
                .map(Binding::shortcut);
        }
    });
    let available = Rc::new(Cell::new(false));
    let busy = Rc::new(Cell::new(false));
    let keys = gtk::EventControllerKey::new();
    keys.set_propagation_phase(gtk::PropagationPhase::Capture);
    let super_pressed = Rc::new(Cell::new(false));
    keys.connect_key_pressed({
        let (captured, available, busy, super_pressed) = (
            captured.clone(),
            available.clone(),
            busy.clone(),
            super_pressed.clone(),
        );
        let apply = apply.clone();
        let copy_bind = copy_bind.clone();
        let field = field.downgrade();
        move |_, key, keycode, state| {
            if matches!(key, gdk::Key::Super_L | gdk::Key::Super_R) {
                super_pressed.set(true);
            }
            let Some((shortcut, label)) =
                capture_shortcut(key, keycode, state, super_pressed.get())
            else {
                return glib::Propagation::Proceed;
            };
            if !busy.get() && available.get() {
                if let Some(field) = field.upgrade() {
                    field.set_text(&label);
                }
                *captured.borrow_mut() = Some(shortcut);
                apply.set_sensitive(true);
                copy_bind.set_sensitive(true);
            }
            glib::Propagation::Stop
        }
    });
    keys.connect_key_released({
        let super_pressed = super_pressed.clone();
        move |_, key, _, _| {
            if matches!(key, gdk::Key::Super_L | gdk::Key::Super_R) {
                super_pressed.set(false);
            }
        }
    });
    field.add_controller(keys);
    let focus = gtk::EventControllerFocus::new();
    let window = window.downgrade();
    focus.connect_enter({
        let window = window.clone();
        move |_| {
            if let Some(surface) = shortcut_surface(&window) {
                surface.inhibit_system_shortcuts(None::<&gdk::Event>);
            }
        }
    });
    focus.connect_leave(move |_| {
        super_pressed.set(false);
        if let Some(surface) = shortcut_surface(&window) {
            surface.restore_system_shortcuts();
        }
    });
    field.add_controller(focus);

    let update = {
        let (apply, remove, status, available, busy, captured) = (
            apply.downgrade(),
            remove.downgrade(),
            status.downgrade(),
            available,
            busy,
            captured.clone(),
        );
        let field = field.downgrade();
        let copy_bind = copy_bind.downgrade();
        let command = command.downgrade();
        let choices = choices.downgrade();
        move |operation, shortcut: Option<Shortcut>| {
            if busy.replace(true) {
                return;
            }
            let (Some(apply), Some(remove), Some(status), Some(field)) = (
                apply.upgrade(),
                remove.upgrade(),
                status.upgrade(),
                field.upgrade(),
            ) else {
                busy.set(false);
                return;
            };
            apply.set_sensitive(false);
            remove.set_sensitive(false);
            let (busy, available, captured) = (busy.clone(), available.clone(), captured.clone());
            let (copy_bind, existing, choices, command) = (
                copy_bind.clone(),
                existing.clone(),
                choices.clone(),
                command.clone(),
            );
            let mut params = json!({"instance": session, "operation": operation});
            if let Some(shortcut) = shortcut {
                params["key"] = json!(shortcut.key);
                params["keycode"] = json!(shortcut.keycode);
                params["modifiers"] = json!(shortcut.modifiers);
            }
            glib::spawn_future_local(async move {
                match request_from_ui::<BindingState>("keybinds.hyprland", params).await {
                    Ok(state) => {
                        if let Some(command) = command.upgrade() {
                            command.set_text(&state.command);
                            command.set_tooltip_text(Some(&state.command));
                        }
                        let has_binding = !state.bindings.is_empty();
                        let multiple = state.bindings.len() > 1;
                        available.set(!has_binding);
                        field.set_visible(!multiple);
                        field.set_sensitive(!has_binding);
                        remove.set_sensitive(has_binding);
                        if let Some(copy_bind) = copy_bind.upgrade() {
                            copy_bind.set_sensitive(has_binding);
                        }
                        let labels = state
                            .bindings
                            .iter()
                            .map(Binding::label)
                            .collect::<Vec<_>>();
                        let model = gtk::StringList::new(
                            &labels.iter().map(String::as_str).collect::<Vec<_>>(),
                        );
                        *existing.borrow_mut() = state.bindings;
                        if let Some(choices) = choices.upgrade() {
                            choices.set_model(Some(&model));
                            choices.set_selected(if has_binding {
                                0
                            } else {
                                gtk::INVALID_LIST_POSITION
                            });
                            choices.set_visible(multiple);
                        }
                        let list = existing.borrow();
                        if let Some(binding) = list.first() {
                            field.set_text(&binding.label());
                            *captured.borrow_mut() = Some(binding.shortcut());
                            status.set_text(if multiple {
                                "Select an old binding and remove it before applying a new shortcut."
                            } else if binding.needs_rebind {
                                "Remove this old binding, then apply the shortcut again to use the running daemon."
                            } else {
                                "Shortcut active for this session."
                            });
                        } else {
                            *captured.borrow_mut() = None;
                            field.set_text("");
                            status.set_text("Choose an unused combination for this session.");
                        }
                    }
                    Err(message) => {
                        apply.set_sensitive(available.get() && captured.borrow().is_some());
                        remove.set_sensitive(!available.get() && operation == "remove");
                        status.set_text(&message);
                    }
                }
                busy.set(false);
            });
        }
    };
    popover.connect_show({
        let update = update.clone();
        move |_| update("get", None)
    });
    remove.connect_clicked({
        let update = update.clone();
        let captured = captured.clone();
        move |_| update("remove", captured.borrow().clone())
    });
    apply.connect_clicked(move |_| {
        let shortcut = captured.borrow().clone();
        if shortcut.is_some() {
            update("set", shortcut);
        }
    });
    popover
}

fn shortcut_surface(window: &glib::WeakRef<adw::ApplicationWindow>) -> Option<gdk::Toplevel> {
    window.upgrade()?.surface()?.dynamic_cast().ok()
}

fn capture_shortcut(
    key: gdk::Key,
    keycode: u32,
    state: gdk::ModifierType,
    super_pressed: bool,
) -> Option<(Shortcut, String)> {
    use gdk::ModifierType as Mod;
    // GDK can report the native XKB Mod4 bit alongside its virtual modifiers.
    const MOD4: u32 = 1 << 6;
    let mut modifiers =
        state & (Mod::SHIFT_MASK | Mod::CONTROL_MASK | Mod::ALT_MASK | Mod::SUPER_MASK);
    if super_pressed
        || state.bits() & MOD4 != 0
        || (state.contains(Mod::META_MASK) && !state.contains(Mod::ALT_MASK))
    {
        modifiers.insert(Mod::SUPER_MASK);
    }
    let key = key.to_lower();
    if !modifiers.intersects(Mod::CONTROL_MASK | Mod::ALT_MASK | Mod::SUPER_MASK)
        || !gtk::accelerator_valid(key, modifiers)
    {
        return None;
    }
    let name = key.name()?;
    if !name
        .bytes()
        .all(|ch| ch.is_ascii_alphanumeric() || ch == b'_')
    {
        return None;
    }
    let key_name = if name.len() == 1 {
        name.to_ascii_uppercase()
    } else {
        name.to_string()
    };
    let mask = [
        (Mod::SHIFT_MASK, 1),
        (Mod::CONTROL_MASK, 4),
        (Mod::ALT_MASK, 8),
        (Mod::SUPER_MASK, 64),
    ]
    .into_iter()
    .filter_map(|(flag, bit)| modifiers.contains(flag).then_some(bit))
    .sum();
    let label = gtk::accelerator_get_label(key, modifiers).to_string();
    Some((
        Shortcut {
            key: key_name,
            keycode,
            modifiers: mask,
            lua: false,
        },
        label,
    ))
}

fn modifier_labels(mask: u32) -> Vec<&'static str> {
    [(64, "Super"), (4, "Ctrl"), (8, "Alt"), (1, "Shift")]
        .into_iter()
        .filter_map(|(bit, label)| (mask & bit != 0).then_some(label))
        .collect()
}
