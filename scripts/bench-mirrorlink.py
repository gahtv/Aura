#!/usr/bin/env python3
"""adb forward tcp:18099 tcp:8099; python3 scripts/bench-mirrorlink.py --token-file ~/aura-pair.json"""
import argparse
import json
import socket
import statistics
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18099)
    parser.add_argument('--token-file', required=True, help='JSON response from /pair/request')
    parser.add_argument('--samples', type=int, default=50)
    args = parser.parse_args()
    with open(args.token_file) as source:
        token = json.load(source)['token']

    def connect():
        return socket.create_connection(('127.0.0.1', args.port), timeout=10)

    def request(method, path):
        start = time.perf_counter()
        with connect() as client:
            client.sendall(f'{method} {path} HTTP/1.0\r\nX-Aura-Token: {token}\r\n\r\n'.encode())
            response = b''
            while chunk := client.recv(65536):
                response += chunk
        expected = b'204' if method == 'POST' else b'200'
        assert response.split()[1] == expected, response
        return (time.perf_counter() - start) * 1000

    print('| Route (no stall) | n | median ms | p95 ms |')
    print('|---|---:|---:|---:|')
    for method, path in [('GET', '/health'), ('GET', '/state'), ('POST', '/navigate?screen=home')]:
        for _ in range(5):
            request(method, path)
        values = sorted(request(method, path) for _ in range(args.samples))
        print(f'| {method} {path} | {len(values)} | {statistics.median(values):.2f} | {values[int(.95 * (len(values) - 1))]:.2f} |')

    print('\n| Half-header hold s | /health ms (3 trials) |')
    print('|---:|---|')
    for hold in (1, 3, 5):
        values = []
        for _ in range(3):
            stalled = connect()
            stalled.sendall(b'GET /health HTTP/1.0\r\nX-Stalled: half')
            time.sleep(.1)
            timer = threading.Timer(hold, stalled.close)
            timer.start()
            try:
                values.append(request('GET', '/health'))
            finally:
                timer.join()
                stalled.close()
        print(f'| {hold} | {", ".join(f"{v:.2f}" for v in values)} |')

    with connect() as stalled:
        stalled.sendall(b'GET /health HTTP/1.0\r\nX-Stalled: half')
        stalled.settimeout(4)
        start = time.perf_counter()
        try:
            closed = stalled.recv(1) == b''
            print(f'\nStalled socket EOF={closed} after {time.perf_counter() - start:.3f} s')
        except socket.timeout:
            print('\nStalled socket still open after 4 s')


if __name__ == '__main__':
    main()
