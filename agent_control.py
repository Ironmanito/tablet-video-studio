#!/usr/bin/env python3
"""
Cliente de Control en Vivo para TabletVideoStudio (Puerto 18450).
Permite cargar proyectos visuales en la línea de tiempo, agregar clips, textos,
audios, reproducir y lanzar exportaciones directamente sobre la UI del WebView.
"""
import json
import subprocess
import sys
import time
import urllib.request

BASE_URL = "http://127.0.0.1:18450"


def ensure_app_awake(bring_to_front: bool = True) -> bool:
    for _ in range(2):
        try:
            with urllib.request.urlopen(f"{BASE_URL}/api/health", timeout=1.5) as resp:
                if resp.status == 200:
                    if bring_to_front:
                        subprocess.run(
                            [
                                "am",
                                "start",
                                "--user",
                                "0",
                                "-n",
                                "com.tabletstudio.videoeditor/.MainActivity",
                            ],
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                        )
                    return True
        except Exception:
            pass
        subprocess.run(
            [
                "am",
                "start",
                "--user",
                "0",
                "-n",
                "com.tabletstudio.videoeditor/.MainActivity",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        time.sleep(2.0)
    return False


def send_agent_command(payload: dict, bring_to_front: bool = True) -> dict:
    ensure_app_awake(bring_to_front=bring_to_front)
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/api/agent/command",
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=5.0) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_agent_state() -> dict:
    ensure_app_awake(bring_to_front=False)
    with urllib.request.urlopen(f"{BASE_URL}/api/agent/state", timeout=3.0) as resp:
        return json.loads(resp.read().decode("utf-8"))


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "demo-partido":
        with open("/sdcard/Download/Proyecto_Partido_y_Publicidad.json", "r") as f:
            design = json.load(f)
        res = send_agent_command(
            {
                "actions": [
                    {"type": "SET_TITLE", "payload": {"title": "Partido + Publicidad Redmi"}},
                    {"type": "LOAD_PROJECT", "payload": {"design": design, "clearFirst": True}},
                ]
            },
            bring_to_front=True,
        )
        print("Comando enviado a la línea de tiempo en vivo:", res)
    elif len(sys.argv) > 1 and sys.argv[1] == "state":
        print(json.dumps(get_agent_state(), indent=2, ensure_ascii=False))
    else:
        print("Uso: python3 agent_control.py [demo-partido | state]")
