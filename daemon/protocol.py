import json
from collections.abc import Mapping
from typing import Any


class ProtocolError(ValueError):
    """Raised when a peer sends a malformed protocol message."""


def encode_message(message: Mapping[str, Any]) -> bytes:
    """Encode one newline-delimited JSON message."""
    return (json.dumps(message, separators=(",", ":")) + "\n").encode("utf-8")


def decode_message(data: bytes) -> dict[str, Any]:
    """Decode and minimally validate one newline-delimited JSON message."""
    try:
        message = json.loads(data)
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ProtocolError("Message is not valid UTF-8 JSON") from exception

    if not isinstance(message, dict):
        raise ProtocolError("Message must be a JSON object")

    kind = message.get("kind")
    if kind not in {"request", "response", "event"}:
        raise ProtocolError("Message has an unknown kind")

    return message


def make_request(
    request_id: str,
    method: str,
    params: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "kind": "request",
        "id": request_id,
        "method": method,
        "params": dict(params or {}),
    }
