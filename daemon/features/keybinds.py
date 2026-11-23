import asyncio
import json
import re
import shlex
import sys
from pathlib import Path


AUDIO_COMMAND = "android-linux-bridge-daemon --toggle-phone-audio"
LEGACY_LUA_DESCRIPTION = "Android Linux Bridge: Toggle phone audio"
LUA_DESCRIPTION = "Android Linux Bridge: Toggle phone audio (managed)"
LUA_EXEC_DISPATCHERS = {"__lua", "HL.Dispatcher(exec_cmd)"}
MODIFIERS = {"SHIFT": 1, "CTRL": 4, "ALT": 8, "SUPER": 64}
SUPPORTED_MODIFIERS = sum(MODIFIERS.values())


def audio_command(socket_path: Path) -> str:
    root = Path(__file__).resolve().parents[2]
    if root == Path("/usr/local/lib/android-linux-bridge"):
        return shlex.join(("env", f"ANDROID_LINUX_BRIDGE_SOCKET={socket_path}", *AUDIO_COMMAND.split()))
    return shlex.join((
        "env", f"ANDROID_LINUX_BRIDGE_SOCKET={socket_path}", f"PYTHONPATH={root}",
        sys.executable, "-m", "daemon.main", "--toggle-phone-audio",
    ))


class HyprlandKeybinds:
    def __init__(self, command=AUDIO_COMMAND):
        self._lock = asyncio.Lock()
        self.command = command

    async def request(self, params):
        instance = params.get("instance")
        if not isinstance(instance, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,200}", instance):
            raise RuntimeError("Invalid Hyprland session.")
        operation = params.get("operation")
        async with self._lock:
            binds = await self._binds(instance)
            owned = [bind for bind in binds if self._is_ours(bind)]
            if operation == "get":
                return self._state(owned)
            if operation == "set":
                key = params.get("key")
                keycode = params.get("keycode")
                modifiers = params.get("modifiers")
                if (not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_]{1,80}", key)
                        or type(keycode) is not int or not 8 <= keycode <= 65535
                        or type(modifiers) is not int or not modifiers & (SUPPORTED_MODIFIERS ^ MODIFIERS["SHIFT"])
                        or modifiers & ~SUPPORTED_MODIFIERS):
                    raise RuntimeError("Invalid Hyprland key combination.")
                matching = self._matching(binds, modifiers, key, keycode)
                if matching and matching != owned:
                    raise RuntimeError("This key combination is already assigned in Hyprland.")
                if len(matching) == 1:
                    return self._state(owned)
                if owned:
                    raise RuntimeError("Remove the current phone audio binding before choosing another.")
                lua = await self._edit(instance, "bind", modifiers, key)
                return self._state([{
                    "modmask": modifiers, "key": key, "dispatcher": "__lua" if lua else "exec",
                    "arg": self.command, "description": LUA_DESCRIPTION if lua else "",
                }])
            if operation == "remove":
                if owned:
                    if "key" in params and "modifiers" in params:
                        selected = [
                            bind for bind in owned if bind["modmask"] == params["modifiers"]
                            and bind["key"] == params["key"]
                        ]
                        if not selected:
                            return self._state(owned)
                    else:
                        selected = owned
                    if len(selected) != 1:
                        raise RuntimeError("Select a single phone audio binding to remove.")
                    bind = selected[0]
                    matching = self._matching(binds, bind["modmask"], bind["key"], bind["keycode"])
                    if len(matching) != 1:
                        raise RuntimeError("Hyprland has multiple bindings for this combination. Review them before removing it.")
                    await self._edit(instance, "unbind", bind["modmask"], bind["key"])
                    owned.remove(bind)
                return self._state(owned)
            raise RuntimeError("Unknown keybind operation.")

    def _is_ours(self, bind):
        command = bind["dispatcher"] == "exec" and bind["arg"] in (AUDIO_COMMAND, self.command)
        lua = bind["dispatcher"] in LUA_EXEC_DISPATCHERS and bind.get("description") in (
            LUA_DESCRIPTION, LEGACY_LUA_DESCRIPTION,
        )
        return ((command or lua)
                and not bind["keycode"] and bool(re.fullmatch(r"[A-Za-z0-9_]{1,80}", bind["key"]))
                and not bind["modmask"] & ~SUPPORTED_MODIFIERS)

    @staticmethod
    def _matching(binds, mask, key, keycode):
        return [
            bind for bind in binds
            if bind["modmask"] == mask
            and (bind["key"].casefold() == key.casefold()
                 or (keycode and bind["keycode"] == keycode)
                 or bind.get("catch_all", False))
        ]

    @staticmethod
    def _mods(mask):
        return " ".join(name for name, bit in MODIFIERS.items() if mask & bit)

    def _state(self, binds):
        bindings = [{
            "key": bind["key"],
            "modifiers": bind["modmask"],
            "lua": bind["dispatcher"] in LUA_EXEC_DISPATCHERS,
            "needs_rebind": (
                bind.get("description") == LEGACY_LUA_DESCRIPTION
                or (bind["dispatcher"] == "exec" and bind["arg"] != self.command)
            ),
        } for bind in binds]
        return {"bindings": bindings, "command": self.command}

    async def _binds(self, instance):
        output = await self._hyprctl(instance, "-j", "binds")
        try:
            binds = json.loads(output)
            fields = {"modmask": int, "key": str, "keycode": int, "dispatcher": str, "arg": str}
            if not isinstance(binds, list) or not all(
                isinstance(bind, dict) and all(type(bind.get(key)) is kind for key, kind in fields.items())
                for bind in binds
            ):
                raise ValueError
        except ValueError:
            raise RuntimeError("Could not read Hyprland's current bindings.") from None
        return binds

    async def _keyword(self, instance, keyword, value):
        result = await self._hyprctl(instance, "keyword", keyword, value)
        if "non-legacy parsers" in result or result == "unknown request":
            return False
        self._check_response(result)
        return True

    async def _edit(self, instance, operation, modifiers, key):
        mods = self._mods(modifiers)
        value = f"{mods}, {key}"
        if operation == "bind":
            value += f", exec, {self.command}"
        if await self._keyword(instance, operation, value):
            return False

        chord = json.dumps(" + ".join([*mods.split(), key]))
        if operation == "bind":
            expression = (
                f"hl.bind({chord}, hl.dsp.exec_cmd({json.dumps(self.command, ensure_ascii=False)}), "
                f"{{ description = {json.dumps(LUA_DESCRIPTION)} }})"
            )
        else:
            expression = f"hl.unbind({chord})"
        self._check_response(await self._hyprctl(instance, "eval", expression))
        return True

    @staticmethod
    def _check_response(result):
        if result != "ok":
            raise RuntimeError(result or "Hyprland did not confirm the change.")

    @staticmethod
    async def _hyprctl(instance, *args):
        try:
            process = await asyncio.create_subprocess_exec(
                "hyprctl", "-i", instance, *args,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            )
        except FileNotFoundError as error:
            raise RuntimeError("hyprctl is unavailable to the daemon.") from error
        try:
            stdout, stderr = await asyncio.wait_for(process.communicate(), 2)
        except (TimeoutError, asyncio.CancelledError):
            if process.returncode is None:
                process.kill()
            await process.communicate()
            raise
        if process.returncode:
            raise RuntimeError((stderr or stdout).decode(errors="replace").strip() or "Hyprland is unavailable.")
        return stdout.decode(errors="replace").strip()
