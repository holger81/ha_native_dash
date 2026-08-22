Inovelli VZM32-SN mmWave quirk (from nickduvall921/mmwave_vis zha_quark).

Copy inovelli/ into your HA custom quirks directory, e.g.:

  /config/zhacustomquirks/inovelli/__init__.py
  /config/zhacustomquirks/inovelli/VZM32SN.py

Ensure configuration.yaml contains:

  zha:
    custom_quirks_path: /config/zhacustomquirks/

Delete any __pycache__ under inovelli/, restart Home Assistant, then ZHA device
page for Secondary Living Room Switch -> Reconfigure.

Verify: a switch entity "mmWave target info report" appears on the device.
Turn it on. Target reports arrive as zha_event command mmwave_target_info.
