import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import UUID


class DeliveryHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def reply(self, code, message):
        body = json.dumps(message).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/deliveries":
            self.reply(404, {})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 65536 or self.headers.get_content_type() != "application/json":
                raise ValueError
            delivery = json.loads(self.rfile.read(size))
            if set(delivery) != {"dispatchId", "channel", "recipient", "title", "content"}:
                raise ValueError
            dispatch_id = UUID(delivery["dispatchId"])
            if delivery["channel"] not in ("EMAIL", "SMS", "KAKAOTALK"):
                raise ValueError
            if not all(isinstance(delivery[k], str) and delivery[k] for k in ("recipient", "title", "content")):
                raise ValueError
        except (ValueError, TypeError, AttributeError):
            self.reply(422, {"status": "PERMANENT_REJECTION"})
            return

        mode = self.server.mode
        if mode == "accepted":
            self.reply(202, {"status": "ACCEPTED", "reference": f"sandbox-{dispatch_id}"})
        elif mode == "permanent-rejection":
            self.reply(422, {"status": "PERMANENT_REJECTION"})
        elif mode == "transient-rejection":
            self.reply(503, {"status": "TRANSIENT_REJECTION"})
        else:
            self.reply(503, {})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="루프백에서 실행하는 모의 알림 공급자")
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--mode", choices=("accepted", "permanent-rejection", "transient-rejection", "ambiguous"),
                        default="accepted")
    options = parser.parse_args()
    with ThreadingHTTPServer(("127.0.0.1", options.port), DeliveryHandler) as server:
        server.mode = options.mode
        print(f"모의 공급자 http://127.0.0.1:{server.server_port}/deliveries ({server.mode})", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
