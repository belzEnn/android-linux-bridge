fn main() {
    let output = std::path::PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    let schema = "io.github.belzenn.AndroidLinuxBridge.gschema.xml";
    std::fs::copy(
        std::path::Path::new("data").join(schema),
        output.join(schema),
    )
    .unwrap();
    let status = std::process::Command::new("glib-compile-schemas")
        .arg(&output)
        .status()
        .unwrap();
    assert!(status.success());
    println!("cargo:rustc-env=GSETTINGS_SCHEMA_DIR={}", output.display());
    println!("cargo:rerun-if-changed=data/{schema}");
}
