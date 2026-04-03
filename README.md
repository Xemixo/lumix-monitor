# Lumix Monitor

An unofficial Android live view monitor app for the Panasonic Lumix S5.

The official Lumix Sync app has distracting UI elements and features which lack the option for hiding them , so this is a custom 
alternative built from scratch by reverse engineering the camera's Wi-Fi API.

## Features
- Live view via UDP stream (~30fps)
- False color overlay for exposure monitoring
- V-Log normalization for correct false color in log footage
- Continues live view during recording
- Auto-reconnect on Wi-Fi drop

## How it works
The app connects to the camera over Wi-Fi Direct (192.168.54.1), switches the 
camera into rec mode via HTTP commands to cam.cgi, starts a UDP stream on 
port 49152, and decodes incoming JPEG frames (offset 254 normally, 238 during 
recording).

## Requirements
- Android 8.0 or higher
- Panasonic Lumix S5
- Camera connected via Wi-Fi Direct

## Usage
1. On the camera, enable Wi-Fi (Menu → Wi-Fi → Wi-Fi Function)
2. Connect your Android phone to the camera's Wi-Fi network
3. Open the app — live view starts automatically

## Known limitations
- Tested only on Lumix S5 (firmware ver.2.9)
- May work on other Lumix cameras but is not tested
- accctrl handshake is not supported on S5 and is skipped

## License
MIT
