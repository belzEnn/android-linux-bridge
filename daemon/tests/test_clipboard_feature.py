import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from daemon.features.clipboard import ClipboardHistory, ClipboardSync, MAX_ITEM_BYTES


class ClipboardHistoryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "clipboard.json"
        self.history = ClipboardHistory(self.path)

    def tearDown(self):
        self.directory.cleanup()

    def test_add_deduplicates_moves_and_persists(self):
        self.assertTrue(self.history.add("first"))
        self.assertTrue(self.history.add("second"))
        self.assertTrue(self.history.add("first"))
        self.assertEqual([item.text for item in self.history.items], ["first", "second"])
        self.assertEqual([item.text for item in ClipboardHistory(self.path).items], ["first", "second"])
        self.assertEqual(self.history.revision, 3)

    def test_rejects_invalid_and_oversized_text(self):
        for value in ("", "bad\0text", "x" * (MAX_ITEM_BYTES + 1)):
            self.assertFalse(self.history.add(value))
        self.assertEqual(self.history.items, [])

    def test_delete_updates_revision(self):
        self.history.add("text")
        item_id = self.history.items[0].id
        self.assertTrue(self.history.delete(item_id))
        self.assertFalse(self.history.delete(item_id))


class ClipboardSyncTest(unittest.TestCase):
    def test_delete_event_is_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            history = ClipboardHistory(Path(directory) / "clipboard.json")
            history.add("text")
            sync = ClipboardSync(SimpleNamespace(sessions=()), history)
            sync.dispatch(SimpleNamespace(), "other", {"id": history.items[0].id})
            self.assertEqual(len(history.items), 1)
            sync.dispatch(SimpleNamespace(), "clipboard.history.delete", {"id": history.items[0].id})
            self.assertEqual(history.items, [])

    def test_android_text_is_added_to_canonical_history(self):
        async def exercise():
            with tempfile.TemporaryDirectory() as directory:
                history = ClipboardHistory(Path(directory) / "clipboard.json")
                sync = ClipboardSync(SimpleNamespace(sessions=()), history)
                sync._set_system_clipboard = AsyncMock()
                session = SimpleNamespace(device_id="phone-123456")
                sync.dispatch(session, "clipboard.history.add", {"text": "From Android"})
                await asyncio.sleep(0)
                self.assertEqual([item.text for item in history.items], ["From Android"])
                sync._set_system_clipboard.assert_awaited_once_with("From Android")

        asyncio.run(exercise())


if __name__ == "__main__":
    unittest.main()
