# Device info

The Overview tab shows live details read from the phone with `device.info`:

- **Storage:** volumes with total and free space.
- **Memory:** total and available RAM.
- **Battery:** level, health, temperature, voltage and power source.
- **System:**
  - Android version and security patch;
  - build ID;
  - CPU cores and ABIs;
  - uptime;
  - display resolution, density and refresh rate;
  - network type, whether it is metered, and IP addresses;
  - locale and time zone.

The details are read on demand, cached in the browser for a minute and never stored on the controller.
The result is validated field by field against the shape in
[`ws-v1.md`](../protocol/ws-v1.md#phase-2-additions-still-v1-all-additive). An invalid answer becomes
`INTERNAL` instead of reaching the page.

## Android permissions

No new permissions. `ACCESS_NETWORK_STATE` (Phase 1) covers the network type and addresses. The sources
are:

- storage: `StatFs` on the `StorageManager` volumes;
- memory: `ActivityManager.MemoryInfo`;
- system: `Build`;
- battery: the sticky `ACTION_BATTERY_CHANGED` intent;
- network: `ConnectivityManager` / `LinkProperties`;
- uptime: `SystemClock`.

Deliberately left out:

- **Wi-Fi network name:** needs location permission.
- **IMEI and serial number:** not available to regular apps.
