#!/usr/bin/env python3
"""Loopback-only feed proxy; reads actual collector records without writing it."""
import argparse,json,sqlite3
from datetime import datetime,timezone
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path

def latest(database):
    now=datetime.now(timezone.utc).isoformat()
    with sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True,timeout=2) as db:
        db.execute('PRAGMA query_only=ON')
        row=db.execute('''SELECT e.payload_json,r.received_at FROM events e
            JOIN responses r ON r.id=e.price_response_id
            WHERE julianday(e.source_updated)<=julianday(?) AND julianday(e.first_seen)<=julianday(?)
              AND julianday(r.received_at)<=julianday(?)
            ORDER BY julianday(e.source_updated) DESC,julianday(e.first_seen) DESC,e.id DESC LIMIT 1''',(now,now,now)).fetchone()
    if row is None:raise ValueError('No actually received market payload')
    return row[0].encode(),row[1]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--database',type=Path,required=True);p.add_argument('--port',type=int,required=True);args=p.parse_args()
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_GET(self):
            if self.path!='/status.json':self.send_error(503,'Auxiliary feed not connected');return
            try:body,received=latest(args.database)
            except (OSError,sqlite3.Error,ValueError):self.send_error(503,'Collector data unavailable');return
            self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Cache-Control','no-store')
            self.send_header('X-Collector-Received-At',received);self.end_headers();self.wfile.write(body)
    ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
if __name__=='__main__':main()