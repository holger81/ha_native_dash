#!/usr/bin/env python3
"""Extract a native-app widget model from greatroom-wall.yaml."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml

ENTITY_RE = re.compile(r"states\[['\"]([a-z0-9_]+\.[a-z0-9_]+)['\"]\]")
ENTITY_ATTR_RE = re.compile(
    r"states\[['\"]([a-z0-9_]+\.[a-z0-9_]+)['\"]\](?:\.state|\.attributes\.([a-z0-9_]+))?"
)
MDI_RE = re.compile(r"mdi:[a-z0-9-]+")
HASH_RE = re.compile(r"#[a-z0-9_]+")


def load_yaml(path: Path) -> dict:
    with path.open() as f:
        return yaml.safe_load(f)


def as_dict_vars(variables) -> dict:
    if variables is None:
        return {}
    if isinstance(variables, dict):
        return {str(k): v for k, v in variables.items()}
    if isinstance(variables, list):
        out = {}
        for item in variables:
            if isinstance(item, dict):
                out.update({str(k): v for k, v in item.items()})
        return out
    return {}


def collect_entities(node, acc: set[str]) -> None:
    if isinstance(node, dict):
        for k, v in node.items():
            if k in {"entity", "activity_entity", "battery", "home_sensor", "work_sensor", "graph"} and isinstance(v, str) and "." in v:
                acc.add(v)
            if k in {"entity_id", "entity_ids"}:
                if isinstance(v, str) and "." in v and not v.startswith("["):
                    for part in v.split(","):
                        part = part.strip()
                        if "." in part:
                            acc.add(part)
                elif isinstance(v, list):
                    for item in v:
                        if isinstance(item, str) and "." in item:
                            acc.add(item)
            if isinstance(v, str):
                acc.update(ENTITY_RE.findall(v))
            else:
                collect_entities(v, acc)
    elif isinstance(node, list):
        for item in node:
            collect_entities(item, acc)
    elif isinstance(node, str):
        acc.update(ENTITY_RE.findall(node))


def collect_icons(node, acc: set[str]) -> None:
    if isinstance(node, dict):
        for v in node.values():
            collect_icons(v, acc)
    elif isinstance(node, list):
        for item in node:
            collect_icons(item, acc)
    elif isinstance(node, str):
        acc.update(MDI_RE.findall(node))


def stringify(value):
    if value is None:
        return None
    if isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def tap_from(card: dict) -> dict | None:
    action = card.get("tap_action") or {}
    if not action:
        return None
    out = {"action": action.get("action")}
    for key in ("service", "navigation_path", "haptic", "entity", "target", "data", "service_data"):
        if key in action:
            out[key] = action[key]
    return out


def display_temp_hum(temp_entity: str | None, hum_entity: str | None, climate_entity: str | None = None) -> dict:
    return {
        "kind": "temp_hum",
        "temp_entity": temp_entity,
        "hum_entity": hum_entity,
        "climate_entity": climate_entity,
    }


def parse_temp_hum_js(text: str | None) -> dict | None:
    if not isinstance(text, str):
        return None
    entities = ENTITY_RE.findall(text)
    climate = next((e for e in entities if e.startswith("climate.")), None)
    temp = next((e for e in entities if "temperature" in e), None)
    hum = next((e for e in entities if "humidity" in e), None)
    if climate or temp:
        return display_temp_hum(temp, hum, climate)
    return None


def chip_visibility(button: dict) -> dict | None:
    styles = button.get("styles") or {}
    display = None
    if isinstance(styles, dict):
        display = (styles.get("button") or {})
        if isinstance(display, dict):
            display = display.get("display")
    if not isinstance(display, str):
        return None
    entity = button.get("entity")
    # Common patterns
    if "is_state" in display and "'on'" in display:
        return {"kind": "state_in", "entity": entity, "states": ["on"]}
    if "is_state" in display and "'run'" in display:
        return {"kind": "state_in", "entity": entity, "states": ["run"]}
    if "docked" in display:
        return {"kind": "state_not", "entity": entity, "states": ["docked"]}
    if "Printing" in display:
        return {"kind": "state_in", "entity": "sensor.octoprint_current_state", "states": ["Printing"]}
    if "envoy_battery_discharging" in display:
        return {"kind": "state_in", "entity": "binary_sensor.envoy_battery_discharging", "states": ["on"]}
    nums = re.findall(r"float\s*<\s*([0-9.]+)", display)
    if nums and entity:
        # display:none when below threshold — show when >= threshold
        return {"kind": "numeric_gte", "entity": entity, "value": float(nums[0])}
    if entity:
        return {"kind": "always"}
    return None


def chip_state(button: dict) -> dict | None:
    state = button.get("state")
    entity = button.get("entity")
    if isinstance(state, dict) and state.get("attribute"):
        return {"kind": "attribute", "entity": entity, "attribute": state["attribute"]}
    if isinstance(state, str):
        if "round(0)" in state and "AQI" in state:
            return {"kind": "number", "entity": entity, "decimals": 0, "suffix": "AQI"}
        if "remaining_program_time" in state:
            return {
                "kind": "minutes_from_hours",
                "entity": "sensor.bosch_dishwasher_remaining_program_time",
                "suffix": "min",
            }
        if "/1000" in state and "kW" in state:
            return {"kind": "number", "entity": entity, "scale": 0.001, "decimals": 2, "suffix": "kW"}
        if "/1000.0" in state and "kWh" in state:
            return {"kind": "number", "entity": entity, "scale": 0.001, "decimals": 3, "suffix": "kWh"}
        if "kW" in state:
            return {"kind": "number", "entity": entity, "decimals": 2, "suffix": "kW"}
        if "%" in state:
            return {"kind": "number", "entity": entity, "decimals": 1, "suffix": "%"}
    layout = button.get("layout")
    if layout == "icon":
        return None
    return {"kind": "state", "entity": entity} if entity else None


def convert_action(action: dict | None) -> dict | None:
    if not isinstance(action, dict):
        return None
    kind = action.get("action")
    if kind in (None, "none"):
        return None
    if kind == "javascript":
        return {"type": "menu_toggle"}
    if kind in ("navigate",):
        return {"type": "navigate", "hash": action.get("navigation_path")}
    if kind == "toggle":
        return {"type": "toggle"}
    if kind in ("more-info",):
        return {"type": "more_info"}
    if kind in ("call-service", "perform-action"):
        service = action.get("service") or action.get("perform_action")
        data = action.get("service_data") or action.get("data") or {}
        target = action.get("target") or {}
        entity_id = data.get("entity_id") or target.get("entity_id")
        extra = {k: v for k, v in data.items() if k != "entity_id"}
        return {
            "type": "call_service",
            "service": service,
            "entity_id": entity_id,
            "data": extra or None,
        }
    return {"type": kind, "raw": action}


def convert_card(card, context: str = "") -> dict | list | None:
    if not isinstance(card, dict):
        return None
    ctype = card.get("type")

    if ctype in ("custom:gap-card",):
        height = card.get("height", 8)
        if isinstance(height, str):
            digits = re.sub(r"[^0-9]", "", height)
            height = int(digits or 8)
        return {"type": "gap", "height": int(height)}

    if ctype == "custom:paper-buttons-row":
        chips = []
        for button in card.get("buttons") or []:
            chips.append(
                {
                    "type": "status_chip",
                    "name": button.get("name"),
                    "icon": button.get("icon"),
                    "entity": button.get("entity"),
                    "layout": button.get("layout"),
                    "tap": convert_action(button.get("tap_action")) or (
                        {"type": "toggle"} if button.get("entity", "").startswith("lock.") else None
                    ),
                    "visibility": chip_visibility(button),
                    "state": chip_state(button),
                    "emphasize_unlocked": button.get("entity") == "lock.front_door",
                }
            )
        return {"type": "chip_row", "chips": chips}

    if ctype == "custom:decluttering-card":
        template = card.get("template")
        variables = as_dict_vars(card.get("variables"))
        return convert_template(template, variables, card)

    if ctype == "custom:button-card":
        return convert_button_card(card)

    if ctype == "custom:bubble-card" and card.get("card_type") == "pop-up":
        return None  # handled at view level

    if ctype == "custom:bubble-card" and card.get("card_type") == "climate":
        return {
            "type": "climate",
            "entity": card.get("entity"),
            "name": card.get("name"),
            "activity_entity": ((card.get("sub_button") or [{}])[0] or {}).get("entity"),
        }

    if ctype == "custom:simple-tabs":
        tabs = []
        for tab in card.get("tabs") or []:
            tabs.append(
                {
                    "title": tab.get("title"),
                    "icon": tab.get("icon"),
                    "cards": flatten_cards(tab.get("cards") or []),
                }
            )
        return {
            "type": "tabs",
            "default_tab": card.get("default_tab", 0),
            "tabs": tabs,
        }

    if ctype in ("vertical-stack", "horizontal-stack"):
        return {
            "type": ctype.replace("-", "_"),
            "cards": flatten_cards(card.get("cards") or []),
        }

    if ctype == "grid":
        return {
            "type": "grid",
            "columns": card.get("columns", 2),
            "cards": flatten_cards(card.get("cards") or []),
        }

    if ctype == "custom:layout-card":
        layout = card.get("layout") or {}
        areas = layout.get("grid-template-areas")
        return {
            "type": "layout_grid",
            "columns": layout.get("grid-template-columns"),
            "rows": layout.get("grid-template-rows"),
            "areas": areas,
            "cards": flatten_cards(card.get("cards") or []),
        }

    if ctype == "custom:local-conditional-card":
        inner = card.get("card")
        converted = convert_card(inner)
        if converted is None:
            return None
        if isinstance(converted, list):
            return converted
        converted["visibility_entity"] = card.get("entity")
        converted["visibility_state"] = card.get("state") or card.get("condition")
        return converted

    if ctype == "conditional":
        conditions = card.get("conditions") or []
        inner = convert_card(card.get("card"))
        if inner is None:
            return None
        if isinstance(inner, list):
            return inner
        inner["conditions"] = conditions
        return inner

    if ctype == "picture-entity":
        return {
            "type": "camera",
            "entity": card.get("entity"),
            "camera_view": card.get("camera_view", "live"),
            "show_state": card.get("show_state", False),
            "show_name": card.get("show_name", False),
        }

    if ctype == "markdown":
        return {"type": "markdown", "content": card.get("content", "")}

    if ctype == "custom:apexcharts-card":
        series = []
        for item in card.get("series") or []:
            series.append(
                {
                    "entity": item.get("entity"),
                    "name": item.get("name"),
                    "type": item.get("type"),
                }
            )
        return {
            "type": "chart",
            "hours_to_show": (card.get("graph_span") or "24h"),
            "series": series,
            "header": (card.get("header") or {}).get("title"),
        }

    if ctype and str(ctype).startswith("energy-"):
        return {"type": ctype.replace("-", "_"), "title": ctype}

    if ctype == "custom:mod-card":
        return convert_card(card.get("card"))

    if ctype == "custom:swipe-card":
        return {
            "type": "swipe",
            "cards": flatten_cards(card.get("cards") or []),
        }

    if ctype == "custom:mini-graph-card":
        ents = card.get("entities") or []
        entity = None
        if ents:
            first = ents[0]
            entity = first.get("entity") if isinstance(first, dict) else first
        return {
            "type": "mini_graph",
            "entity": entity,
            "hours": card.get("hours_to_show", 12),
        }

    if ctype == "heading":
        return {"type": "heading", "text": card.get("heading") or card.get("name")}

    # Fallback: keep enough to render a generic entity tile
    entity = card.get("entity")
    if entity or ctype:
        return {
            "type": "generic",
            "card_type": ctype,
            "entity": entity,
            "name": card.get("name"),
            "icon": card.get("icon"),
            "tap": convert_action(card.get("tap_action")),
        }
    return None


def flatten_cards(cards) -> list:
    out = []
    for card in cards or []:
        converted = convert_card(card)
        if converted is None:
            continue
        if isinstance(converted, list):
            out.extend(converted)
        else:
            out.append(converted)
    return out


def convert_template(template: str, variables: dict, card: dict | None = None) -> dict:
    tap = convert_action((card or {}).get("tap_action"))
    base = {
        "name": stringify(variables.get("name")),
        "icon": stringify(variables.get("icon")),
        "entity": stringify(variables.get("entity")),
        "background": stringify(variables.get("background")),
    }
    if template == "person_card_new":
        return {
            "type": "person",
            "entity": variables.get("entity"),
            "name": variables.get("name"),
            "battery": variables.get("battery"),
            "home_sensor": variables.get("home_sensor"),
            "show_entity_picture": True,
            "tap": tap or {"type": "more_info"},
        }
    if template == "light_slider":
        return {**base, "type": "light_slider", "tap": tap or {"type": "toggle"}, "hold": {"type": "more_info"}}
    if template == "light_toggle":
        return {**base, "type": "light_toggle", "tap": tap or {"type": "toggle"}, "hold": {"type": "more_info"}}
    if template == "cover_toggle":
        return {**base, "type": "cover_toggle", "tap": tap or {"type": "toggle"}, "hold": {"type": "more_info"}}
    if template == "vent_toggle_small":
        return {**base, "type": "vent_toggle", "tap": {"type": "vent_tilt_toggle"}, "hold": {"type": "more_info"}}
    if template == "vents_group_toggle":
        ids = variables.get("entity_ids") or variables.get("entity")
        if isinstance(ids, str):
            ids = [s.strip() for s in ids.split(",") if s.strip()]
        return {
            **base,
            "type": "vents_group",
            "entity_ids": ids,
        }
    if template == "room_climate_card":
        return {
            "type": "climate",
            "entity": variables.get("entity"),
            "name": variables.get("name"),
            "activity_entity": variables.get("activity_entity"),
        }
    if template == "room_conditions":
        parsed = parse_temp_hum_js(variables.get("temp"))
        return {
            "type": "room_conditions",
            "entity": variables.get("entity"),
            "display": parsed or display_temp_hum(variables.get("entity"), None),
        }
    if template == "sensor_big":
        return {**base, "type": "sensor_big", "label": stringify(variables.get("label"))}
    if template == "sensor_big_graph":
        return {
            **base,
            "type": "sensor_graph",
            "label": stringify(variables.get("label")),
            "graph_entity": stringify(variables.get("graph")),
        }
    if template == "sensor_big_percentage":
        return {**base, "type": "sensor_percentage", "label": stringify(variables.get("label"))}
    if template == "sensor_small":
        return {**base, "type": "sensor_small", "label": stringify(variables.get("label"))}
    if template == "button_toggle":
        return {**base, "type": "button_toggle", "tap": tap or {"type": "toggle"}}
    if template == "button_toggle_small":
        return {**base, "type": "button_toggle_small", "tap": tap or {"type": "toggle"}}
    if template == "button_trigger":
        return {**base, "type": "button_trigger", "tap": tap}
    if template in {"chips_big", "chips_big_active", "chips_medium", "chips_medium_active", "chips_small", "chips_small_active"}:
        return {**base, "type": "action_chip", "style": template, "tap": tap}
    if template == "vacuum_button":
        name = stringify(variables.get("name")) or stringify((card or {}).get("name"))
        entity = stringify(variables.get("entity")) or stringify((card or {}).get("entity"))
        widget = {
            **base,
            "type": "vacuum_button",
            "name": name,
            "entity": entity,
            "label": stringify(variables.get("label")),
            "tap": tap or {"type": "toggle"},
        }
        if (name or "").lower() == "stop" and not entity:
            widget["entity"] = "vacuum.s8_maxv_ultra"
            widget["tap"] = {
                "type": "call_service",
                "service": "vacuum.stop",
                "entity_id": "vacuum.s8_maxv_ultra",
                "data": None,
            }
        if (name or "").lower() == "start" and entity == "script.clean_house":
            widget["tap"] = {
                "type": "call_service",
                "service": "script.turn_on",
                "entity_id": "script.clean_house",
                "data": None,
            }
        return widget
    if template == "room_card":
        return convert_room_card(variables)
    return {**base, "type": template or "generic", "tap": tap}


def convert_room_card(variables: dict) -> dict:
    name = variables.get("name")
    if isinstance(name, str):
        name = re.sub(r"\[\[\[\s*return\s+'([^']+)'\s*\]\]\]", r"\1", name)
        name = name.replace("<br>", "\n").replace("`", "'").strip()
    path = variables.get("path")
    display = parse_temp_hum_js(variables.get("state"))
    color = variables.get("color")
    if isinstance(color, str):
        color = color.replace("var(--", "").replace(")", "")
    radius = variables.get("radius")
    return {
        "type": "room_card",
        "name": name,
        "icon": variables.get("icon"),
        "path": path,
        "display": display,
        "accent": color,
        "radius": radius,
        "tap": {"type": "navigate", "hash": path},
    }


def convert_button_card(card: dict) -> dict:
    template = card.get("template")
    variables = as_dict_vars(card.get("variables"))
    if template:
        widget = convert_template(template, variables, card)
        area = (card.get("view_layout") or {}).get("grid-area")
        if area:
            widget["grid_area"] = area
        if card.get("entity") and not widget.get("entity"):
            widget["entity"] = card.get("entity")
        if card.get("name") and not widget.get("name"):
            widget["name"] = card.get("name")
        if card.get("icon") and not widget.get("icon"):
            widget["icon"] = card.get("icon")
        tap = convert_action(card.get("tap_action"))
        if tap:
            widget["tap"] = tap
        return widget

    entity = card.get("entity")
    name = card.get("name")
    icon = card.get("icon")
    area = (card.get("view_layout") or {}).get("grid-area")

    if area == "button":
        return {
            "type": "menu_button",
            "entity": entity,
            "icon": icon or "mdi:menu",
            "tap": {"type": "menu_toggle"},
            "hold": convert_action(card.get("hold_action")),
            "grid_area": area,
        }
    if area == "weather" or (isinstance(entity, str) and entity.startswith("weather.")):
        return {
            "type": "weather_header",
            "entity": entity,
            "temp_entity": "sensor.st_00063154_temperature",
            "sun_entity": "sun.sun",
            "tap": convert_action(card.get("tap_action")),
            "grid_area": area,
        }

    # Media player / TV card
    if isinstance(entity, str) and entity.startswith("media_player."):
        return {
            "type": "media_player",
            "entity": entity,
            "companion_entity": "media_player.living_room_appletv",
            "name": name or "TV",
            "icon": icon,
            "tap": convert_action(card.get("tap_action")) or {"type": "toggle"},
        }

    # Group vents (label JS referencing multiple covers)
    label = card.get("label")
    if isinstance(label, str) and "vent" in label.lower():
        ids = ENTITY_RE.findall(label) or ENTITY_RE.findall(str(card.get("tap_action")))
        service = None
        tap = card.get("tap_action") or {}
        return {
            "type": "vents_group",
            "name": name,
            "icon": icon or "mdi:air-filter",
            "entity_ids": list(dict.fromkeys(ids)),
            "tap": convert_action(tap),
        }

    widget = {
        "type": "entity_button",
        "entity": entity,
        "name": name if not isinstance(name, str) or "[[[" not in name else None,
        "icon": icon,
        "label": label if isinstance(label, str) and "[[[" not in label else None,
        "tap": convert_action(card.get("tap_action")),
        "hold": convert_action(card.get("hold_action")),
        "show_entity_picture": card.get("show_entity_picture"),
    }
    if area:
        widget["grid_area"] = area
    display = parse_temp_hum_js(str(name) + str(label) + str(card.get("custom_fields")))
    if display:
        widget["display"] = display
    return widget


def convert_popup(card: dict) -> dict:
    icon_color = None
    styles = card.get("styles") or ""
    m = re.search(r"background-color:var\(--([a-z-]+)\)", styles)
    if m:
        icon_color = m.group(1)
    return {
        "type": "popup",
        "name": card.get("name"),
        "icon": card.get("icon"),
        "hash": card.get("hash"),
        "accent": icon_color,
        "cards": flatten_cards(card.get("cards") or []),
    }


ROOM_AREAS = {
    "emilia",
    "greatroom",
    "jonathan",
    "hallway",
    "office",
    "mainbath",
    "secondbath",
    "mainbed",
    "guestroom",
}


def walk_nodes(node):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from walk_nodes(value)
    elif isinstance(node, list):
        for item in node:
            yield from walk_nodes(item)


def convert_home(view: dict) -> dict:
    people = []
    chips = None
    rooms = []
    header_cards = []
    popups = []
    home_nodes = []

    for card in view.get("cards") or []:
        if card.get("type") == "custom:bubble-card" and card.get("card_type") == "pop-up":
            popups.append(convert_popup(card))
            continue
        converted = convert_card(card)
        if converted is None:
            continue
        home_nodes.append(converted)

    for node in walk_nodes(home_nodes):
        kind = node.get("type")
        area = node.get("grid_area")
        if kind == "person":
            people.append(node)
        elif kind == "menu_button":
            header_cards.append(node)
        elif kind == "weather_header" and area == "weather":
            header_cards.append(node)
        elif kind == "chip_row":
            chips = node
        elif kind == "room_card" or area in ROOM_AREAS:
            rooms.append(node)

    # Keep CSS-grid occupancy order from the Lovelace layout.
    order = {name: index for index, name in enumerate(ROOM_AREAS)}
    rooms.sort(key=lambda item: order.get(item.get("grid_area"), 99))

    return {
        "title": view.get("title", "Home"),
        "people": people,
        "header": header_cards,
        "chips": chips,
        "rooms": rooms,
        "popups": popups,
    }


def main() -> int:
    src = Path(sys.argv[1] if len(sys.argv) > 1 else Path.home() / "Projects/ha_dashboards/greatroom-wall.yaml")
    dest = Path(sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/dashboard.json")
    data = load_yaml(src)
    views = data.get("views") or []
    home = convert_home(views[0])

    entities: set[str] = set()
    icons: set[str] = set()
    collect_entities(home, entities)
    collect_icons(home, icons)

    model = {
        "version": 1,
        "source": str(src.name),
        "home": home,
        "entities": sorted(entities),
        "icons": sorted(icons),
    }
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(model, indent=2))
    print(f"Wrote {dest} ({dest.stat().st_size} bytes)")
    print(f"rooms={len(home['rooms'])} popups={len(home['popups'])} people={len(home['people'])}")
    print(f"entities={len(entities)} icons={len(icons)}")
    print("popup hashes:", [p.get("hash") for p in home["popups"]])
    types = {}

    def walk(n):
        if isinstance(n, dict):
            t = n.get("type")
            if t:
                types[t] = types.get(t, 0) + 1
            for v in n.values():
                walk(v)
        elif isinstance(n, list):
            for i in n:
                walk(i)

    walk(home)
    print("widget types:", json.dumps(types, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
