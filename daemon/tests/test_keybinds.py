import shlex
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

from daemon.features.keybinds import (
    AUDIO_COMMAND,
    LEGACY_LUA_DESCRIPTION,
    LUA_DESCRIPTION,
    HyprlandKeybinds,
    audio_command,
)


def binding(key="B", keycode=0, command=AUDIO_COMMAND):
    return {"key": key, "keycode": keycode, "modmask": 64, "dispatcher": "exec", "arg": command}


class HyprlandKeybindTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.keybinds = HyprlandKeybinds()
        self.keybinds._binds = AsyncMock(return_value=[])
        self.keybinds._keyword = AsyncMock(return_value=True)
        self.request = {"instance": "test_instance", "key": "B", "keycode": 56, "modifiers": 64}

    async def test_super_binding_uses_keyword_and_is_idempotent(self):
        result = await self.keybinds.request(dict(self.request, operation="set"))
        self.assertEqual(result["bindings"], [
            {"key": "B", "modifiers": 64, "lua": False, "needs_rebind": False},
        ])
        self.keybinds._keyword.assert_awaited_once_with(
            "test_instance", "bind", f"SUPER, B, exec, {AUDIO_COMMAND}"
        )
        self.keybinds._binds.return_value = [binding()]
        self.assertEqual(await self.keybinds.request(dict(self.request, operation="set")), result)
        self.assertEqual(self.keybinds._keyword.await_count, 1)

    async def test_conflicts_by_name_and_physical_keycode_are_left_intact(self):
        for existing in (binding("b", command="firefox"), binding("", 56, "firefox")):
            with self.subTest(existing=existing):
                self.keybinds._binds.return_value = [existing]
                with self.assertRaisesRegex(RuntimeError, "already assigned"):
                    await self.keybinds.request(dict(self.request, operation="set"))
        self.keybinds._keyword.assert_not_awaited()

    async def test_remove_never_unbinds_duplicates_or_foreign_bindings(self):
        alien = binding(command="firefox")
        self.keybinds._binds.return_value = [alien, binding()]
        with self.assertRaisesRegex(RuntimeError, "multiple bindings"):
            await self.keybinds.request(dict(self.request, operation="remove"))
        self.keybinds._binds.return_value = [alien]
        await self.keybinds.request(dict(self.request, operation="remove"))
        self.keybinds._keyword.assert_not_awaited()

        self.keybinds._binds.return_value = [binding()]
        self.assertEqual(
            await self.keybinds.request(dict(self.request, operation="remove")),
            {"bindings": [], "command": AUDIO_COMMAND},
        )
        self.keybinds._keyword.assert_awaited_once_with("test_instance", "unbind", "SUPER, B")

    async def test_lua_fallback_uses_a_description_to_find_the_binding_again(self):
        self.keybinds._keyword.return_value = False
        self.keybinds._hyprctl = AsyncMock(return_value="ok")
        result = await self.keybinds.request(dict(self.request, operation="set"))
        self.assertTrue(result["bindings"][0]["lua"])
        instance, method, script = self.keybinds._hyprctl.await_args.args
        self.assertEqual((instance, method), ("test_instance", "eval"))
        self.assertIn('hl.bind("SUPER + B", hl.dsp.exec_cmd(', script)
        lua = dict(binding(command="3"), dispatcher="HL.Dispatcher(exec_cmd)", description=LUA_DESCRIPTION)
        self.keybinds._binds.return_value = [lua]
        self.assertEqual(await self.keybinds.request(dict(self.request, operation="get")), result)
        await self.keybinds.request(dict(self.request, operation="remove"))
        self.keybinds._hyprctl.assert_awaited_with("test_instance", "eval", 'hl.unbind("SUPER + B")')

    async def test_multiple_old_lua_bindings_can_be_removed_one_at_a_time(self):
        old = {"dispatcher": "HL.Dispatcher(exec_cmd)", "description": LEGACY_LUA_DESCRIPTION}
        self.keybinds._binds.return_value = [dict(binding(key), **old) for key in ("B", "X")]
        result = await self.keybinds.request(dict(self.request, operation="get"))
        self.assertEqual([row["key"] for row in result["bindings"]], ["B", "X"])
        self.assertTrue(all(row["needs_rebind"] for row in result["bindings"]))
        with self.assertRaisesRegex(RuntimeError, "Select a single"):
            await self.keybinds.request({"instance": "test_instance", "operation": "remove"})
        self.keybinds._keyword.assert_not_awaited()
        result = await self.keybinds.request(dict(self.request, operation="remove"))
        self.assertEqual([row["key"] for row in result["bindings"]], ["X"])
        self.keybinds._keyword.assert_awaited_once_with("test_instance", "unbind", "SUPER, B")

    def test_source_command_targets_the_current_socket_and_python_environment(self):
        command = shlex.split(audio_command(Path("/tmp/test bridge.sock")))
        self.assertEqual(command[:2], ["env", "ANDROID_LINUX_BRIDGE_SOCKET=/tmp/test bridge.sock"])
        self.assertEqual(command[-4:], [sys.executable, "-m", "daemon.main", "--toggle-phone-audio"])
        self.assertEqual(command[2], f"PYTHONPATH={Path(__file__).resolve().parents[2]}")
