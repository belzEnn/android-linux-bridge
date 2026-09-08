APP_NAME := android-linux-bridge
APP_ID := io.github.belzenn.AndroidLinuxBridge

PREFIX ?= /usr/local
BINDIR := $(PREFIX)/bin
SHAREDIR := $(PREFIX)/share
LIBDIR := $(PREFIX)/lib/$(APP_NAME)

SYSTEMD_USER_DIR := $(SHAREDIR)/systemd/user
APPLICATIONS_DIR := $(SHAREDIR)/applications
ICONS_DIR := $(SHAREDIR)/icons/hicolor/scalable/apps
SCHEMAS_DIR := $(SHAREDIR)/glib-2.0/schemas

PYTHON := python3
CARGO := cargo

DESKTOP_DIR := desktop
ANDROID_DIR := android
DAEMON_DIR := daemon
PACKAGING_DIR := packaging

DESKTOP_BIN := $(DESKTOP_DIR)/target/release/android-linux-bridge-desktop

DESKTOP_FILE := $(PACKAGING_DIR)/android-linux-bridge.desktop
SERVICE_FILE := $(PACKAGING_DIR)/android-linux-bridge.service
DAEMON_LAUNCHER := $(PACKAGING_DIR)/android-linux-bridge-daemon

ANDROID_APK_DEBUG := $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk
ANDROID_APK_RELEASE := $(ANDROID_DIR)/app/build/outputs/apk/release/app-release.apk

LOGO := logo.svg
ANDROID_RES := $(ANDROID_DIR)/app/src/main/res

.PHONY: all \
	build linux desktop daemon \
	android apk apk-debug apk-release \
	icons android-icon \
	install uninstall \
	enable disable restart \
	clean clean-linux clean-android \
	release check

all: build

build: linux

linux: daemon desktop

daemon:
	@echo "Checking Python daemon..."
	$(PYTHON) -m compileall -q $(DAEMON_DIR)

desktop:
	@echo "Building Rust desktop application..."
	$(CARGO) build \
		--release \
		--locked \
		--manifest-path $(DESKTOP_DIR)/Cargo.toml

android: apk

apk: apk-debug

apk-debug: android-icon
	@echo "Building Android debug APK..."
	cd $(ANDROID_DIR) && ./gradlew assembleDebug
	@echo
	@echo "APK:"
	@echo "$(ANDROID_APK_DEBUG)"

apk-release: android-icon
	@echo "Building Android release APK..."
	cd $(ANDROID_DIR) && ./gradlew assembleRelease
	@echo
	@echo "APK:"
	@echo "$(ANDROID_APK_RELEASE)"

icons: android-icon

android-icon:
	@test -f "$(LOGO)" || \
		(echo "Error: $(LOGO) not found"; exit 1)

	@command -v inkscape >/dev/null 2>&1 || \
		(echo "Error: Inkscape is required to generate Android icons"; exit 1)

	@echo "Generating Android launcher icons from $(LOGO)..."

	rm -f $(ANDROID_RES)/mipmap-*/ic_launcher.webp
	rm -f $(ANDROID_RES)/mipmap-*/ic_launcher_round.webp
	rm -f $(ANDROID_RES)/mipmap-*/ic_launcher.png
	rm -f $(ANDROID_RES)/mipmap-*/ic_launcher_round.png

	mkdir -p $(ANDROID_RES)/mipmap-mdpi
	mkdir -p $(ANDROID_RES)/mipmap-hdpi
	mkdir -p $(ANDROID_RES)/mipmap-xhdpi
	mkdir -p $(ANDROID_RES)/mipmap-xxhdpi
	mkdir -p $(ANDROID_RES)/mipmap-xxxhdpi

	inkscape "$(LOGO)" \
		--export-type=png \
		--export-width=48 \
		--export-height=48 \
		--export-filename="$(ANDROID_RES)/mipmap-mdpi/ic_launcher.png"

	inkscape "$(LOGO)" \
		--export-type=png \
		--export-width=72 \
		--export-height=72 \
		--export-filename="$(ANDROID_RES)/mipmap-hdpi/ic_launcher.png"

	inkscape "$(LOGO)" \
		--export-type=png \
		--export-width=96 \
		--export-height=96 \
		--export-filename="$(ANDROID_RES)/mipmap-xhdpi/ic_launcher.png"

	inkscape "$(LOGO)" \
		--export-type=png \
		--export-width=144 \
		--export-height=144 \
		--export-filename="$(ANDROID_RES)/mipmap-xxhdpi/ic_launcher.png"

	inkscape "$(LOGO)" \
		--export-type=png \
		--export-width=192 \
		--export-height=192 \
		--export-filename="$(ANDROID_RES)/mipmap-xxxhdpi/ic_launcher.png"

	cp "$(ANDROID_RES)/mipmap-mdpi/ic_launcher.png" \
	   "$(ANDROID_RES)/mipmap-mdpi/ic_launcher_round.png"

	cp "$(ANDROID_RES)/mipmap-hdpi/ic_launcher.png" \
	   "$(ANDROID_RES)/mipmap-hdpi/ic_launcher_round.png"

	cp "$(ANDROID_RES)/mipmap-xhdpi/ic_launcher.png" \
	   "$(ANDROID_RES)/mipmap-xhdpi/ic_launcher_round.png"

	cp "$(ANDROID_RES)/mipmap-xxhdpi/ic_launcher.png" \
	   "$(ANDROID_RES)/mipmap-xxhdpi/ic_launcher_round.png"

	cp "$(ANDROID_RES)/mipmap-xxxhdpi/ic_launcher.png" \
	   "$(ANDROID_RES)/mipmap-xxxhdpi/ic_launcher_round.png"

install:
	@test -f "$(DESKTOP_BIN)" || \
		(echo "Error: desktop binary not found. Run 'make' first."; exit 1)

	@test -f "$(LOGO)" || \
		(echo "Error: $(LOGO) not found"; exit 1)

	@test -f "$(DESKTOP_FILE)" || \
		(echo "Error: $(DESKTOP_FILE) not found"; exit 1)

	@test -f "$(SERVICE_FILE)" || \
		(echo "Error: $(SERVICE_FILE) not found"; exit 1)

	@test -f "$(DAEMON_LAUNCHER)" || \
		(echo "Error: $(DAEMON_LAUNCHER) not found"; exit 1)

	@test -f "requirements.txt" || \
		(echo "Error: requirements.txt not found"; exit 1)

	@echo "Installing Android Linux Bridge..."

	install -Dm755 "$(DESKTOP_BIN)" \
		"$(DESTDIR)$(BINDIR)/android-linux-bridge-desktop"

	install -d "$(DESTDIR)$(LIBDIR)"

	rm -rf "$(DESTDIR)$(LIBDIR)/$(DAEMON_DIR)"
	cp -r "$(DAEMON_DIR)" "$(DESTDIR)$(LIBDIR)/"

	install -Dm644 requirements.txt \
		"$(DESTDIR)$(LIBDIR)/requirements.txt"

	@echo "Creating Python virtual environment..."
	rm -rf "$(DESTDIR)$(LIBDIR)/venv"
	$(PYTHON) -m venv "$(DESTDIR)$(LIBDIR)/venv"

	@echo "Installing daemon dependencies..."
	"$(DESTDIR)$(LIBDIR)/venv/bin/pip" install \
		-r "$(DESTDIR)$(LIBDIR)/requirements.txt"

	install -Dm755 "$(DAEMON_LAUNCHER)" \
		"$(DESTDIR)$(BINDIR)/android-linux-bridge-daemon"

	install -Dm644 "$(DESKTOP_FILE)" \
		"$(DESTDIR)$(APPLICATIONS_DIR)/$(APP_ID).desktop"

	install -Dm644 "$(SERVICE_FILE)" \
		"$(DESTDIR)$(SYSTEMD_USER_DIR)/android-linux-bridge.service"

	install -Dm644 "$(LOGO)" \
		"$(DESTDIR)$(ICONS_DIR)/$(APP_ID).svg"

	@if [ -d "$(DESKTOP_DIR)/data" ]; then \
		install -d "$(DESTDIR)$(SCHEMAS_DIR)"; \
		find "$(DESKTOP_DIR)/data" \
			-name '*.gschema.xml' \
			-exec install -Dm644 {} "$(DESTDIR)$(SCHEMAS_DIR)/" \; ; \
	fi

	@if command -v glib-compile-schemas >/dev/null 2>&1; then \
		glib-compile-schemas \
			"$(DESTDIR)$(SCHEMAS_DIR)"; \
	fi

	@if command -v update-desktop-database >/dev/null 2>&1; then \
		update-desktop-database \
			"$(DESTDIR)$(APPLICATIONS_DIR)" \
			2>/dev/null || true; \
	fi

	@if command -v gtk-update-icon-cache >/dev/null 2>&1; then \
		gtk-update-icon-cache \
			-f \
			-t \
			"$(DESTDIR)$(SHAREDIR)/icons/hicolor" \
			2>/dev/null || true; \
	fi

	@echo
	@echo "Installed:"
	@echo "  Desktop: $(BINDIR)/android-linux-bridge-desktop"
	@echo "  Daemon:  $(BINDIR)/android-linux-bridge-daemon"
	@echo "  Icon:    $(ICONS_DIR)/$(APP_ID).svg"
	@echo "  Desktop entry: $(APPLICATIONS_DIR)/$(APP_ID).desktop"

enable:
	systemctl --user daemon-reload
	systemctl --user enable --now android-linux-bridge.service

disable:
	systemctl --user disable --now android-linux-bridge.service || true

restart:
	systemctl --user daemon-reload
	systemctl --user restart android-linux-bridge.service

uninstall:
	@echo "Uninstalling Android Linux Bridge..."

	rm -f "$(DESTDIR)$(BINDIR)/android-linux-bridge-desktop"
	rm -f "$(DESTDIR)$(BINDIR)/android-linux-bridge-daemon"
	rm -rf "$(DESTDIR)$(LIBDIR)"
	rm -f "$(DESTDIR)$(APPLICATIONS_DIR)/$(APP_ID).desktop"
	rm -f "$(DESTDIR)$(SYSTEMD_USER_DIR)/android-linux-bridge.service"
	rm -f "$(DESTDIR)$(ICONS_DIR)/$(APP_ID).svg"

	@if command -v update-desktop-database >/dev/null 2>&1; then \
		update-desktop-database \
			"$(DESTDIR)$(APPLICATIONS_DIR)" \
			2>/dev/null || true; \
	fi

	@if command -v gtk-update-icon-cache >/dev/null 2>&1; then \
		gtk-update-icon-cache \
			-f \
			-t \
			"$(DESTDIR)$(SHAREDIR)/icons/hicolor" \
			2>/dev/null || true; \
	fi

	@echo "Uninstalled."

release: clean linux apk-release
	@echo
	@echo "Release build complete:"
	@echo "Linux:"
	@echo "  $(DESKTOP_BIN)"
	@echo "Android:"
	@echo "  $(ANDROID_APK_RELEASE)"

check:
	$(PYTHON) -m compileall -q $(DAEMON_DIR)
	$(CARGO) check \
		--locked \
		--manifest-path $(DESKTOP_DIR)/Cargo.toml
	cd $(ANDROID_DIR) && ./gradlew test

clean: clean-linux clean-android

clean-linux:
	$(CARGO) clean \
		--manifest-path $(DESKTOP_DIR)/Cargo.toml
	find $(DAEMON_DIR) \
		-type d \
		-name __pycache__ \
		-prune \
		-exec rm -rf {} +

clean-android:
	cd $(ANDROID_DIR) && ./gradlew clean
