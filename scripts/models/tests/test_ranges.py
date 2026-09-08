import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import pathlib
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from download_ranges import download_ranges


class RangeDownloadTest(unittest.TestCase):
    def test_parallel_ranges_publish_only_the_exact_expected_file(self):
        payload = bytes(range(256)) * 100
        state = {"bad_range": False, "corrupt": False}

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                start, end = map(int, self.headers["Range"].split("=")[1].split("-"))
                self.send_response(206)
                self.send_header("Content-Range", f"bytes {0 if state['bad_range'] else start}-{end}/{len(payload)}")
                self.end_headers()
                self.wfile.write((b"x" * (end - start + 1)) if state["corrupt"] else payload[start:end + 1])

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                target = pathlib.Path(directory) / "model.bin"
                file = {"url": f"http://127.0.0.1:{server.server_port}/model", "size": len(payload),
                        "sha256": hashlib.sha256(payload).hexdigest()}
                download_ranges(file, target, workers=3, chunk_size=1024, retries=1)
                self.assertEqual(payload, target.read_bytes())
                target.unlink()
                state["bad_range"] = True
                with self.assertRaisesRegex(ValueError, "Range"):
                    download_ranges(file, target, workers=3, chunk_size=1024, retries=1)
                self.assertFalse(target.exists())
                state.update(bad_range=False, corrupt=True)
                with self.assertRaisesRegex(ValueError, "SHA-256"):
                    download_ranges(file, target, workers=3, chunk_size=1024, retries=1)
                self.assertFalse(target.exists())
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
