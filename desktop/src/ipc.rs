use std::env;
use std::path::PathBuf;
use std::sync::mpsc::{Receiver, Sender};
use std::thread;
use std::time::{Duration, Instant};

use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;
use tokio::runtime::Builder;
use tokio::time::{sleep, timeout};
use uuid::Uuid;

use crate::model::{Battery, Device, PairingRequest, TrustedDevice};

#[derive(Debug)]
pub enum Command {
    RespondPairing { id: String, accepted: bool },
    RevokeTrusted { device_id: String },
    ResetTrusted,
    Stop,
}

#[derive(Debug)]
pub enum Event {
    Online,
    Offline,
    Devices(Vec<Device>),
    Battery(Option<Battery>),
    Pairings(Vec<PairingRequest>),
    Trusted(Vec<TrustedDevice>),
    Error(String),
}

pub fn socket_path() -> PathBuf {
    if let Some(path) = env::var_os("ANDROID_LINUX_BRIDGE_SOCKET") {
        return PathBuf::from(path);
    }
    if let Some(path) = env::var_os("XDG_RUNTIME_DIR") {
        return PathBuf::from(path).join("android-linux-bridge.sock");
    }
    let uid = unsafe { libc::geteuid() };
    PathBuf::from(format!("/tmp/android-linux-bridge-{uid}.sock"))
}

pub fn spawn(command_rx: Receiver<Command>, event_tx: Sender<Event>) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let runtime = Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(run(command_rx, event_tx));
    })
}

async fn run(command_rx: Receiver<Command>, event_tx: Sender<Event>) {
    let path = socket_path();
    let mut client: Option<Client> = None;
    let mut last_full_refresh = Instant::now() - Duration::from_secs(5);

    loop {
        if client.is_none() {
            while let Ok(command) = command_rx.try_recv() {
                if matches!(command, Command::Stop) {
                    return;
                }
                let _ = event_tx.send(Event::Error("Daemon is offline".to_string()));
            }
            match UnixStream::connect(&path).await {
                Ok(stream) => {
                    client = Some(Client::new(stream));
                    let _ = event_tx.send(Event::Online);
                    last_full_refresh = Instant::now() - Duration::from_secs(5);
                }
                Err(_) => {
                    let _ = event_tx.send(Event::Offline);
                    sleep(Duration::from_secs(1)).await;
                    continue;
                }
            }
        }

        let Some(active_client) = client.as_mut() else {
            continue;
        };
        let mut disconnected = false;

        while let Ok(command) = command_rx.try_recv() {
            let result = match command {
                Command::RespondPairing { id, accepted } => {
                    active_client
                        .request::<Value>(
                            "pairing.respond",
                            json!({"id": id, "accepted": accepted}),
                        )
                        .await
                }
                Command::RevokeTrusted { device_id } => {
                    active_client
                        .request::<Value>("pairing.revoke", json!({"device_id": device_id}))
                        .await
                }
                Command::ResetTrusted => {
                    active_client
                        .request::<Value>("pairing.reset", json!({}))
                        .await
                }
                Command::Stop => return,
            };
            if let Err(error) = result {
                let _ = event_tx.send(Event::Error(error));
                disconnected = true;
                break;
            }
            last_full_refresh = Instant::now() - Duration::from_secs(5);
        }

        if !disconnected {
            match active_client
                .request::<Vec<PairingRequest>>("pairing.pending", json!({}))
                .await
            {
                Ok(requests) => {
                    let _ = event_tx.send(Event::Pairings(requests));
                }
                Err(_) => disconnected = true,
            }
        }

        if !disconnected && last_full_refresh.elapsed() >= Duration::from_secs(5) {
            disconnected = !refresh(active_client, &event_tx).await;
            last_full_refresh = Instant::now();
        }

        if disconnected {
            client = None;
            let _ = event_tx.send(Event::Offline);
        }

        sleep(Duration::from_secs(1)).await;
    }
}

async fn refresh(client: &mut Client, event_tx: &Sender<Event>) -> bool {
    let devices = match client
        .request::<Vec<Device>>("devices.list", json!({}))
        .await
    {
        Ok(value) => value,
        Err(_) => return false,
    };
    let has_active = devices.iter().any(|device| device.active);
    let _ = event_tx.send(Event::Devices(devices));

    if has_active {
        match client.request::<Battery>("battery.get", json!({})).await {
            Ok(battery) => {
                let _ = event_tx.send(Event::Battery(Some(battery)));
            }
            Err(_) => return false,
        }
    } else {
        let _ = event_tx.send(Event::Battery(None));
    }

    match client
        .request::<Vec<TrustedDevice>>("pairing.trusted", json!({}))
        .await
    {
        Ok(devices) => {
            let _ = event_tx.send(Event::Trusted(devices));
            true
        }
        Err(_) => false,
    }
}

struct Client {
    reader: BufReader<tokio::net::unix::OwnedReadHalf>,
    writer: tokio::net::unix::OwnedWriteHalf,
}

impl Client {
    fn new(stream: UnixStream) -> Self {
        let (reader, writer) = stream.into_split();
        Self {
            reader: BufReader::new(reader),
            writer,
        }
    }

    async fn request<T: DeserializeOwned>(
        &mut self,
        method: &str,
        params: Value,
    ) -> Result<T, String> {
        timeout(Duration::from_secs(5), self.exchange(method, params))
            .await
            .map_err(|_| "Daemon request timed out".to_string())?
    }

    async fn exchange<T: DeserializeOwned>(
        &mut self,
        method: &str,
        params: Value,
    ) -> Result<T, String> {
        let request_id = Uuid::new_v4().simple().to_string();
        let message = json!({
            "kind": "request",
            "id": request_id,
            "method": method,
            "params": params,
        });
        let mut encoded = serde_json::to_vec(&message).map_err(|error| error.to_string())?;
        encoded.push(b'\n');
        self.writer
            .write_all(&encoded)
            .await
            .map_err(|error| error.to_string())?;
        self.writer
            .flush()
            .await
            .map_err(|error| error.to_string())?;

        let mut line = String::new();
        let bytes = self
            .reader
            .read_line(&mut line)
            .await
            .map_err(|error| error.to_string())?;
        if bytes == 0 {
            return Err("Daemon closed the IPC connection".to_string());
        }
        let response: Value = serde_json::from_str(&line).map_err(|error| error.to_string())?;
        if response.get("kind").and_then(Value::as_str) != Some("response")
            || response.get("id").and_then(Value::as_str) != Some(request_id.as_str())
        {
            return Err("Daemon returned an invalid response".to_string());
        }
        if let Some(error) = response.get("error") {
            return Err(error
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("Unknown daemon error")
                .to_string());
        }
        serde_json::from_value(response.get("result").cloned().unwrap_or(Value::Null))
            .map_err(|error| error.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_response_deserializes() {
        let device: Device = serde_json::from_value(json!({
            "host": "192.0.2.1",
            "port": 4242,
            "device_id": "pixel",
            "model": "Pixel 8",
            "active": true
        }))
        .unwrap();
        assert_eq!(device.model, "Pixel 8");
        assert!(device.active);
    }

    #[test]
    fn request_round_trip() {
        let runtime = Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            let path = PathBuf::from(format!(
                "/tmp/android-linux-bridge-test-{}.sock",
                Uuid::new_v4().simple()
            ));
            let listener = tokio::net::UnixListener::bind(&path).unwrap();
            let server = tokio::spawn(async move {
                let (stream, _) = listener.accept().await.unwrap();
                let (reader, mut writer) = stream.into_split();
                let mut reader = BufReader::new(reader);
                let mut line = String::new();
                reader.read_line(&mut line).await.unwrap();
                let request: Value = serde_json::from_str(&line).unwrap();
                let response = json!({
                    "kind": "response",
                    "id": request["id"],
                    "result": {"level": 72, "charging": false}
                });
                let mut encoded = serde_json::to_vec(&response).unwrap();
                encoded.push(b'\n');
                writer.write_all(&encoded).await.unwrap();
            });
            let stream = UnixStream::connect(&path).await.unwrap();
            let mut client = Client::new(stream);
            let battery = client
                .request::<Battery>("battery.get", json!({}))
                .await
                .unwrap();
            assert_eq!(battery.level, 72);
            assert!(!battery.charging);
            server.await.unwrap();
            std::fs::remove_file(path).unwrap();
        });
    }
}
