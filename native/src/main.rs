//! Pico 4 Face Tracking as a native OSCFT for VRC module: the same headset protocol, pairing and expression mapping
//! as the .NET module in `module/`, as a standalone program speaking OSCFT's module protocol (no .NET). Only runs in
//! OSCFT for VRC, not in VRCFaceTracking.

mod connector;
mod exchange;
mod listener;
mod net;
mod pairing;
mod payload;

use connector::{Connector, PORT, Received};
use fb_module_sdk::{Claims, Context, Event, Init, LogLevel, Map, Module, TrackingFrame, Value};
use listener::Listener;
use pairing::Pairing;
use serde_json::json;
use std::net::Ipv4Addr;
use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, Instant};

/// How long `initialize` waits for a headset before leaving eye and face tracking to other modules.
const HEADSET_PROBE: Duration = Duration::from_secs(5);
/// A connected headset that sends only pings this long has stopped tracking; the face goes neutral.
const NO_FRAMES_TIMEOUT: Duration = Duration::from_secs(2);
/// Wait before trying again after the network failed.
const RETRY_PAUSE: Duration = Duration::from_secs(2);

const ICON: &[u8] = include_bytes!("../../module/PicoFacialDataModule/Assets/icon.png");

mod field {
    pub const HEADSET: &str = "headset";
    pub const PAIRING: &str = "pairing";
    pub const ADDRESS: &str = "address";
    pub const EYE: &str = "eye";
    pub const FACE: &str = "face";
    pub const CLAIM_WITHOUT_HEADSET: &str = "claimWithoutHeadset";
    pub const PUPIL_THRESHOLD: &str = "pupilOpennessThreshold";
}

mod notification {
    pub const PORT: &str = "port";
    pub const RECEIVING: &str = "receiving";
}

/// The module's settings, from OSCFT's settings panel (the .NET module reads `PicoFacialDataModule.json` instead).
struct Settings {
    address: Option<Ipv4Addr>,
    eye: bool,
    face: bool,
    claim_without_headset: bool,
    pupil_threshold: f32,
}

impl Settings {
    fn read(values: &Map<String, Value>) -> Result<Self, String> {
        let flag = |key: &str, default: bool| values.get(key).and_then(Value::as_bool).unwrap_or(default);
        let address = match values.get(field::ADDRESS).and_then(Value::as_str).map(str::trim) {
            None | Some("") => None,
            Some(text) => Some(text.parse().map_err(|_| format!("Headset address \"{text}\" is not an IPv4 address."))?),
        };
        Ok(Self {
            address,
            eye: flag(field::EYE, true),
            face: flag(field::FACE, true),
            claim_without_headset: flag(field::CLAIM_WITHOUT_HEADSET, false),
            pupil_threshold: pupil_threshold(values),
        })
    }
}

fn pupil_threshold(values: &Map<String, Value>) -> f32 {
    values.get(field::PUPIL_THRESHOLD).and_then(Value::as_f64).unwrap_or(0.8) as f32
}

fn schema(paired: bool) -> Value {
    let restart = "Applies when the modules restart.";
    json!({ "fields": [
        { "key": field::HEADSET, "label": "Headset", "type": "text", "default": if paired {
            "Not reported yet. If this stays after tracking starts, update Pico Face Tracking on the headset."
        } else {
            "Unknown: the headset only reports its model once paired by code."
        } },
        { "key": field::PAIRING, "label": "Pairing", "type": "text", "default": if paired {
            "Paired. To pair again, press \"Pair by code\" in Pico Face Tracking on the headset."
        } else {
            "Not paired. To pair, press \"Pair by code\" in Pico Face Tracking on the headset."
        } },
        { "key": field::EYE, "label": "Eye tracking", "type": "bool", "default": true, "description": restart },
        { "key": field::FACE, "label": "Face tracking", "type": "bool", "default": true, "description": restart },
        { "key": field::ADDRESS, "label": "Headset address", "type": "string", "default": "",
          "description": format!("Leave empty to find the headset on the local network. {restart}") },
        { "key": field::CLAIM_WITHOUT_HEADSET, "label": "Claim without a headset", "type": "bool", "default": false,
          "description": format!("Claim eye and face tracking even when no headset answers within 5 seconds of starting. Off: another module gets them instead. {restart}") },
        { "key": field::PUPIL_THRESHOLD, "label": "Pupil size: minimum eye openness", "type": "float",
          "default": 0.8, "min": 0.0, "max": 1.0, "step": 0.05,
          "description": "The pupil size is only updated while the eye is at least this open." },
    ] })
}

fn write_text(ctx: &Context, key: &str, text: impl Into<String>) {
    let mut values = Map::new();
    values.insert(key.into(), Value::String(text.into()));
    ctx.write_settings(values);
}

/// Messages from the pairing listener, applied on the module thread.
struct Inbox {
    events: Receiver<listener::Event>,
    offered: bool,
    paired: Option<Pairing>,
}

impl Inbox {
    /// Applies pending listener events; true when a new pairing key arrived.
    fn pump(&mut self, ctx: &Context) -> bool {
        while let Ok(event) = self.events.try_recv() {
            match event {
                listener::Event::Log(level, message) => ctx.log(level, message),
                listener::Event::Status(text) => write_text(ctx, field::PAIRING, text),
                listener::Event::Offered => self.offered = true,
                listener::Event::Paired(p) => self.paired = Some(p),
            }
        }
        self.paired.is_some()
    }
}

#[derive(Default)]
struct Pico {
    address: Option<Ipv4Addr>,
    pupil_threshold: f32,
    claims: Claims,
    connector: Option<Connector>,
    listener: Option<Listener>,
    inbox: Option<Inbox>,
    frame: TrackingFrame,
}

impl Pico {
    fn create_connector(&self, ctx: &Context, pairing: Option<Pairing>) -> std::io::Result<Connector> {
        if pairing.is_some() {
            let path = pairing::key_path().map(|p| p.display().to_string()).unwrap_or_default();
            ctx.log(LogLevel::Information, format!("Paired by key ({path}), using protocol version 2."));
        }
        Connector::new(self.address, pairing)
    }

    fn start(&mut self, ctx: &Context, available: Claims) -> Result<Claims, String> {
        let settings = Settings::read(&ctx.settings())?;
        self.address = settings.address;
        self.pupil_threshold = settings.pupil_threshold;
        let wanted = Claims { eye: available.eye && settings.eye, expression: available.expression && settings.face };
        if !wanted.eye && !wanted.expression {
            return Ok(Claims::default());
        }

        let pairing = Pairing::load()?;
        let paired = pairing.is_some();
        let connector = match self.create_connector(ctx, pairing) {
            Ok(c) => c,
            Err(e) if !paired && matches!(e.kind(), std::io::ErrorKind::AddrInUse | std::io::ErrorKind::PermissionDenied) => {
                // Only the original protocol listens on the fixed port; a paired module uses any free one.
                ctx.log(LogLevel::Warning, format!("Opening UDP port {PORT} failed: {e}"));
                ctx.notify(
                    LogLevel::Error,
                    notification::PORT,
                    format!("UDP port {PORT} is used by another program, so the module cannot receive tracking from the headset. Close that program (for example another Pico module) and restart the modules."),
                );
                return Ok(Claims::default());
            }
            Err(e) => return Err(format!("Opening the connection failed: {e}")),
        };
        ctx.clear_notification(notification::PORT);
        self.connector = Some(connector);

        let (tx, rx) = mpsc::channel();
        self.listener = Some(Listener::spawn(self.address, PORT, tx).map_err(|e| format!("Starting pairing failed: {e}"))?);
        self.inbox = Some(Inbox { events: rx, offered: false, paired: None });

        // OSCFT hands eye and face tracking to the first module that claims them, so claim only for a headset that
        // is there.
        if !settings.claim_without_headset && !self.find_headset(ctx) {
            ctx.log(
                LogLevel::Information,
                format!("No PICO headset answered within {} seconds, so eye and face tracking are left to other modules. To use the PICO: turn tracking on in Pico Face Tracking on the headset, then restart the modules.", HEADSET_PROBE.as_secs()),
            );
            self.shut_down();
            return Ok(Claims::default());
        }
        Ok(wanted)
    }

    /// Waits up to [`HEADSET_PROBE`] for a headset to answer the discovery or to offer to pair.
    fn find_headset(&mut self, ctx: &Context) -> bool {
        let (Some(connector), Some(inbox)) = (&mut self.connector, &mut self.inbox) else { return false };
        let until = Instant::now() + HEADSET_PROBE;
        let mut abort = || inbox.pump(ctx) || inbox.offered || ctx.is_stopping();
        match connector.establish(until, &mut abort) {
            Ok(Some(headset)) => {
                ctx.log(LogLevel::Information, format!("Connection established to: {}", headset.ip()));
                return true;
            }
            Ok(None) => {}
            Err(e) => ctx.log(LogLevel::Debug, format!("Looking for the headset: {e}")),
        }
        // A headset on its pairing screen does not answer the discovery.
        inbox.offered || inbox.paired.is_some()
    }

    fn neutral(&mut self, ctx: &Context) {
        payload::reset_to_neutral(&mut self.frame, self.claims.eye, self.claims.expression);
        ctx.send_frame(&self.frame);
    }

    fn shut_down(&mut self) {
        self.listener = None;
        self.connector = None;
    }
}

impl Module for Pico {
    fn supported(&self) -> Claims {
        Claims { eye: true, expression: true }
    }

    fn initialize(&mut self, ctx: &Context, available: Claims) -> Init {
        ctx.declare_settings(&schema(pairing::key_path().is_some_and(|p| p.is_file())));
        let claims = self.start(ctx, available).unwrap_or_else(|e| {
            ctx.log(LogLevel::Critical, format!("Initialization failed: {e}"));
            self.shut_down();
            Claims::default()
        });
        self.claims = claims;
        Init { claims, name: "Pico 4 Face Tracking".into(), images: vec![ICON.to_vec()] }
    }

    fn run(&mut self, ctx: &Context) {
        let mut inbox = self.inbox.take().expect("initialize set up pairing");
        let mut searching = false;
        let mut receiving = false;
        let mut neutral_without_frames = false;
        let mut last_frame = Instant::now();
        // Not yet established when the headset was only seen offering to pair.
        let mut announced = self.connector.as_ref().is_some_and(Connector::is_established);

        while !ctx.is_stopping() {
            while let Some(event) = ctx.try_event() {
                if event == Event::SettingsChanged {
                    self.pupil_threshold = pupil_threshold(&ctx.settings());
                }
            }
            // A headset paired by code: switch to its key without a restart (dropping the old connector stops it).
            if let Some(pairing) = inbox.paired.take() {
                self.connector = None;
                match self.create_connector(ctx, Some(pairing)) {
                    Ok(c) => self.connector = Some(c),
                    Err(e) => ctx.log(LogLevel::Error, format!("Opening the connection failed: {e}")),
                }
                if receiving {
                    receiving = false;
                    self.neutral(ctx);
                }
                announced = false;
            }
            let Some(connector) = &mut self.connector else {
                if !ctx.sleep(RETRY_PAUSE) {
                    break;
                }
                let pairing = Pairing::load().unwrap_or_else(|e| {
                    ctx.log(LogLevel::Error, e);
                    None
                });
                match self.create_connector(ctx, pairing) {
                    Ok(c) => self.connector = Some(c),
                    Err(e) => ctx.log(LogLevel::Warning, format!("Opening the connection failed: {e}")),
                }
                continue;
            };
            let mut abort = || inbox.pump(ctx) || ctx.is_stopping();

            if !connector.is_established() {
                if !searching {
                    ctx.log(LogLevel::Information, "Establishing");
                    searching = true;
                }
                match connector.establish(Instant::now() + Duration::from_secs(2), &mut abort) {
                    Ok(Some(headset)) => {
                        searching = false;
                        announced = true;
                        last_frame = Instant::now();
                        neutral_without_frames = false;
                        ctx.log(LogLevel::Information, format!("Connection established to: {}", headset.ip()));
                    }
                    Ok(None) => {}
                    Err(e) => {
                        ctx.log(LogLevel::Warning, format!("Looking for the headset failed: {e}"));
                        ctx.sleep(RETRY_PAUSE);
                    }
                }
                continue;
            }
            if !announced {
                announced = true;
                last_frame = Instant::now();
            }

            let mut on_model = |model: &str| {
                ctx.log(LogLevel::Information, format!("Headset: {model}"));
                write_text(ctx, field::HEADSET, model);
            };
            match connector.receive(&mut abort, &mut on_model) {
                Ok(Received::Frame) => {
                    payload::apply(connector.frame(), &mut self.frame, self.pupil_threshold);
                    ctx.send_frame(&self.frame);
                    last_frame = Instant::now();
                    // Once until the headset stops sending; a quick reconnect does not repeat it.
                    if !receiving {
                        receiving = true;
                        ctx.notify(LogLevel::Information, notification::RECEIVING, "Receiving data from Pico 4 headset");
                    }
                    if neutral_without_frames {
                        neutral_without_frames = false;
                        ctx.log(LogLevel::Information, "The headset sends tracking data again.");
                    }
                }
                Ok(Received::Nothing) => {
                    // Still connected (it answers pings) but not tracking: taken off, or tracking paused on it.
                    if !neutral_without_frames && last_frame.elapsed() >= NO_FRAMES_TIMEOUT {
                        neutral_without_frames = true;
                        ctx.log(
                            LogLevel::Information,
                            "The headset is connected but sends no tracking data, the face and eyes are neutral until it does.",
                        );
                        self.neutral(ctx);
                    }
                }
                Ok(Received::Aborted) => {}
                Err(connector::Died) => {
                    connector.reset();
                    receiving = false;
                    ctx.log(LogLevel::Information, "The headset stopped sending, the face and eyes are neutral until it is back.");
                    self.neutral(ctx);
                }
            }
        }
    }

    fn teardown(&mut self, _ctx: &Context) {
        self.shut_down();
    }
}

fn main() {
    fb_module_sdk::run(|_data_dir| Ok(Pico::default()));
}
