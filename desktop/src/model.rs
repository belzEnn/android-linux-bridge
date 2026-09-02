use serde::Deserialize;

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
pub struct Device {
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub device_id: String,
    #[serde(default = "default_model")]
    pub model: String,
    #[serde(default)]
    pub active: bool,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
pub struct Battery {
    pub level: u8,
    pub charging: bool,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
pub struct PairingRequest {
    pub id: String,
    pub model: String,
    pub address: String,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
pub struct TrustedDevice {
    pub device_id: String,
    pub model: String,
}

fn default_model() -> String {
    "Android device".to_string()
}
