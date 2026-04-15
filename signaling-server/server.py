#!/usr/bin/env python3
"""
Simple WebSocket signaling server for WebRTC screen casting.

Relays offer/answer/ice messages between connected peers.
When two peers are connected, sends a "Ready" state to both,
which triggers the sender (Android) to create an offer.

Usage:
    pip install websockets
    python server.py [port]

Default port: 8080
"""

import asyncio
import json
import sys
import websockets

clients = set()


async def handler(websocket):
    clients.add(websocket)
    peer_id = id(websocket)
    print(f"[+] Client connected: {peer_id} (total: {len(clients)})")

    if len(clients) >= 2:
        ready_msg = json.dumps({"type": "state", "state": "Ready"})
        for client in clients:
            try:
                await client.send(ready_msg)
            except Exception:
                pass
        print(f"[*] Both peers connected, sent Ready signal")

    try:
        async for message in websocket:
            msg_type = "unknown"
            try:
                parsed = json.loads(message)
                msg_type = parsed.get("type", "unknown")
            except Exception:
                pass

            print(f"[>] From {peer_id}: type={msg_type} ({len(message)} bytes)")

            for client in clients:
                if client != websocket:
                    try:
                        await client.send(message)
                    except Exception:
                        pass
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        clients.discard(websocket)
        print(f"[-] Client disconnected: {peer_id} (total: {len(clients)})")


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"=== WebRTC Signaling Server ===")
    print(f"Listening on ws://0.0.0.0:{port}")
    print(f"Press Ctrl+C to stop\n")
    async with websockets.serve(handler, "0.0.0.0", port):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
