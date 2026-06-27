import serial
import sys
import time

try:
    port = serial.Serial('COM6', 115200, timeout=1)
    port.reset_input_buffer()
    
    print("=== Monitoring started. Try connecting from the fresh phone now. ===\n", flush=True)
    
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            data = port.read(port.in_waiting or 1)
            if data:
                decoded = data.decode('ascii', errors='replace')
                sys.stdout.write(decoded)
                sys.stdout.flush()
        except Exception:
            pass
        time.sleep(0.1)
    
    print("\n=== Monitoring ended (60s) ===", flush=True)
finally:
    port.close()
