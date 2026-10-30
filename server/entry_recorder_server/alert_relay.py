"""Motion-alert relay dispatch (change `add-motion-notification-to-other-phone`).

The relay endpoint receives a detected-motion event from the monitoring phone and hands it to the
recipient's UnifiedPush endpoint. Pushing to a caller-supplied URL is a server-side request forgery
surface and the endpoint URL is itself a bearer capability (anyone holding it can push notifications
to that phone), so the rules live here as pure functions: the route in ``main.py`` stays thin and these
stay testable without a network or a running server.
"""

from __future__ import annotations

import ipaddress
import json
import socket
from dataclasses import dataclass
from enum import Enum
from typing import Mapping
from urllib.parse import urlsplit

import requests

# The discriminator the receiving app checks before it turns a push body into a notification. Anything
# else is ignored, so the field must stay stable across both implementations.
MOTION_ALERT_KIND = "entry_recorder.motion"

DEFAULT_TIMEOUT_SECONDS = 8.0

# Hostnames that resolve to a local or on-link target through the resolver rather than an IP literal.
_LOCAL_NAME_SUFFIXES = (".local", ".internal", ".localhost")


class EndpointReason(str, Enum):
    """Why an endpoint was accepted or refused; surfaced to the client so it can log a useful line."""

    OK = "ok"
    MALFORMED = "malformed"
    NOT_HTTPS = "not_https"
    PRIVATE_TARGET = "private_target"


@dataclass(frozen=True)
class EndpointDecision:
    reason: EndpointReason
    host: str = ""

    @property
    def allowed(self) -> bool:
        return self.reason is EndpointReason.OK


class PushDeliveryError(Exception):
    """The distributor refused, timed out or could not be reached. Maps to a 502, never to a 200."""


def validate_push_endpoint(url: str, allow_insecure: bool = False) -> EndpointDecision:
    """Judge a caller-supplied push endpoint before the server posts anything to it.

    https is required so a bearer capability is not handed over in cleartext; ``allow_insecure`` is the
    operator escape hatch for a self-hosted distributor on the LAN. Non-routable, loopback, link-local and
    reserved literal targets are refused because they would let an authenticated client make the server
    dial inside its own network. Credentials embedded in the URL are refused outright: they would end up
    in ``requests``' auth handling and in error text.
    """
    try:
        parts = urlsplit(url)
        host = (parts.hostname or "").strip().lower()
    except ValueError:
        return EndpointDecision(EndpointReason.MALFORMED)

    if not parts.scheme or not host or parts.username or parts.password:
        return EndpointDecision(EndpointReason.MALFORMED, host)

    if parts.scheme != "https" and not allow_insecure:
        return EndpointDecision(EndpointReason.NOT_HTTPS, host)

    if host == "localhost" or host.endswith(_LOCAL_NAME_SUFFIXES):
        return EndpointDecision(EndpointReason.PRIVATE_TARGET, host)

    default_port = 443 if parts.scheme == "https" else 80
    try:
        resolved = socket.getaddrinfo(host, parts.port or default_port)[0][4][0]
    except (OSError, UnicodeError):
        # Unresolvable: let the POST itself fail as a delivery error rather than guess at the reason.
        return EndpointDecision(EndpointReason.OK, host)

    try:
        address = ipaddress.ip_address(resolved)
    except ValueError:
        return EndpointDecision(EndpointReason.OK, host)

    if not allow_insecure and (
        address.is_private
        or address.is_loopback
        or address.is_link_local
        or address.is_reserved
        or address.is_multicast
        or address.is_unspecified
    ):
        return EndpointDecision(EndpointReason.PRIVATE_TARGET, host)

    return EndpointDecision(EndpointReason.OK, host)


def redact_endpoint(url: str) -> str:
    """Scheme + host only.

    A UnifiedPush endpoint carries its token in the path or query, so this is what reaches a log line or a
    response body. Anything unparsable collapses to a fixed marker instead of echoing the input.
    """
    try:
        parts = urlsplit(url)
        host = (parts.hostname or "").lower()
    except ValueError:
        return "<malformed-endpoint>"
    if not parts.scheme or not host:
        return "<malformed-endpoint>"
    port = f":{parts.port}" if parts.port else ""
    return f"{parts.scheme}://{host}{port}"


def motion_alert_payload(device_name: str, timestamp_ms: int, monitor_name: str = "") -> Mapping[str, object]:
    """The JSON contract the receiving app parses: see the payload shape in the change's design.md."""
    return {
        "kind": MOTION_ALERT_KIND,
        "deviceName": device_name,
        "monitorName": monitor_name,
        "timestampMs": int(timestamp_ms),
    }


def build_alert_body(payload: Mapping[str, object]) -> bytes:
    """Compact UTF-8 JSON for the push body; distributors hand these bytes over untouched."""
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def post_to_endpoint(url: str, body: bytes, timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS) -> None:
    """Deliver one alert, raising ``PushDeliveryError`` for any outcome that is not a 2xx answer.

    Blocking by design: the route calls it through ``run_in_threadpool`` so the event loop keeps serving.
    """
    try:
        response = requests.post(
            url,
            data=body,
            headers={"Content-Type": "application/json"},
            timeout=timeout_seconds,
        )
    except requests.RequestException as exc:
        raise PushDeliveryError(f"push endpoint could not be reached: {type(exc).__name__}") from exc

    if not 200 <= response.status_code < 300:
        raise PushDeliveryError(f"push endpoint answered {response.status_code}")
