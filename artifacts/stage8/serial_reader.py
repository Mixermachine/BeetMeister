import argparse, serial, sys, time

parser = argparse.ArgumentParser()
parser.add_argument("port")
parser.add_argument("--baud", type=int, default=115200)
parser.add_argument("--max-seconds", type=float, default=30.0,
                    help="Exit after this many seconds even if data is still arriving. 0 = run until Ctrl-C.")
parser.add_argument("--idle-exit", type=float, default=0.0,
                    help="Exit after this many seconds of zero received data. 0 = disabled.")
parser.add_argument("--read-timeout", type=float, default=0.2)
args = parser.parse_args()

ser = serial.Serial(args.port, args.baud, timeout=args.read_timeout)
start = time.monotonic()
last_data = start
try:
    while True:
        if args.max_seconds and (time.monotonic() - start) >= args.max_seconds:
            break
        data = ser.read(4096)
        if data:
            sys.stdout.buffer.write(data)
            sys.stdout.flush()
            last_data = time.monotonic()
        elif args.idle_exit and (time.monotonic() - last_data) >= args.idle_exit:
            break
except KeyboardInterrupt:
    pass

