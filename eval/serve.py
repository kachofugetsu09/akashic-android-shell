"""在隔离 LAN 实例加载真实 phone-use 插件，控制入口只接受本机 bearer token。"""
import argparse
from dataclasses import asdict
import importlib.util
import ipaddress
import os
from pathlib import Path
import secrets
import sys

from fastapi import Request
from fastapi.responses import HTMLResponse, JSONResponse
import uvicorn


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin", type=Path, required=True)
    parser.add_argument("--bind", required=True, help="测试电脑的 LAN IP")
    parser.add_argument("--peer", required=True, help="唯一允许连接的手机 IP")
    parser.add_argument("--port", type=int, default=18769)
    parser.add_argument("--token-file", type=Path, required=True, help="新建文件，不覆盖已有凭据")
    args = parser.parse_args()
    for address in (args.bind, args.peer):
        if not ipaddress.ip_address(address).is_private or ipaddress.ip_address(address).is_unspecified:
            parser.error("仅支持明确的私有 LAN 地址")
    root = args.plugin.resolve(strict=True)
    spec = importlib.util.spec_from_file_location("phone_eval_plugin", root / "contract.py",
        submodule_search_locations=[str(root)])
    if spec is None or spec.loader is None:
        raise RuntimeError("不能加载插件")
    package = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = package
    spec.loader.exec_module(package)
    from phone_eval_plugin.bridge import PhoneBridge, PhoneNodeError
    from phone_eval_plugin.server import create_phone_app

    token = secrets.token_urlsafe(32)
    fd = os.open(args.token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as file:
        file.write(token)
    bridge = PhoneBridge()
    app = create_phone_app(bridge)

    @app.middleware("http")
    async def authorize(request: Request, call_next):
        local = request.client.host in ("127.0.0.1", "::1", args.bind)
        if request.client.host != args.peer and not local:
            return JSONResponse({"detail": "isolated test peer required"}, status_code=403)
        if request.method == "GET" and request.url.path == "/api/shell/state":
            return JSONResponse({"chatReady": False, "isolated": True, "service": "phone evaluation only"})
        authorized = request.headers.get("authorization") == "Bearer " + token
        if request.url.path.startswith("/control/") and not (local and authorized):
            return JSONResponse({"detail": "local bearer token required"}, status_code=403)
        if request.url.path == "/":
            response = HTMLResponse("<p>Isolated phone evaluation. No production workspace.</p>")
            response.set_cookie("phone-eval", token, httponly=True, samesite="strict", max_age=86400)
            return response
        if request.cookies.get("phone-eval") != token and not (local and authorized):
            return JSONResponse({"detail": "open the isolated root page first"}, status_code=403)
        return await call_next(request)

    @app.get("/control/status")
    async def status():
        return {"devices": [asdict(device) for device in bridge.list_devices()]}

    @app.post("/control/rpc")
    async def rpc(request: Request):
        frame = await request.json()
        if frame.get("op") not in ("observe", "action", "finish"):
            return JSONResponse({"detail": "unsupported op"}, status_code=400)
        try:
            return await bridge.rpc(frame["device_id"], frame["op"], frame.get("payload", {}),
                timeout_ms=frame.get("timeout_ms", 15000))
        except PhoneNodeError as error:
            return JSONResponse({"detail": str(error)}, status_code=409)

    async def only_http(scope, receive, send):
        # HTTP middleware 不保护 WebSocket；本 eval 只使用 Android 的 SSE 路径。
        if scope["type"] == "websocket":
            await send({"type": "websocket.close", "code": 1008})
            return
        await app(scope, receive, send)

    uvicorn.run(only_http, host=args.bind, port=args.port, lifespan="off", access_log=False)


if __name__ == "__main__":
    main()
