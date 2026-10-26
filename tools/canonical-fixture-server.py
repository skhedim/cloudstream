#!/usr/bin/env python3
"""Loopback-only HTTP range server for generated playback fixtures, forwarded with adb reverse."""
import argparse
import functools
import http.server
import re


class RangeHandler(http.server.SimpleHTTPRequestHandler):
    def send_head(self):
        self.remaining = None
        match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
        if not match:
            return super().send_head()
        path = self.translate_path(self.path)
        try:
            stream = open(path, "rb")
            size = stream.seek(0, 2)
            start = int(match.group(1))
            end = min(int(match.group(2)) if match.group(2) else size - 1, size - 1)
            if start > end or start >= size:
                stream.close()
                self.send_error(416)
                return None
            stream.seek(start)
            self.remaining = end - start + 1
            self.send_response(206)
            self.send_header("Content-Type", self.guess_type(path))
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.send_header("Content-Length", str(self.remaining))
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            return stream
        except OSError:
            self.send_error(404)
            return None

    def copyfile(self, source, outputfile):
        if self.remaining is None:
            return super().copyfile(source, outputfile)
        remaining = self.remaining
        try:
            while remaining > 0:
                chunk = source.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                outputfile.write(chunk)
                remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Seeking/cancelling closes the previous request.


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True)
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    handler = functools.partial(RangeHandler, directory=args.directory)
    http.server.ThreadingHTTPServer(("127.0.0.1", args.port), handler).serve_forever()
