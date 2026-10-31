#!/system/bin/sh
# Stand probe: can the emulator reach the host wire proxy through the emulator host alias?
printf 'GET /health HTTP/1.0\r\nHost: 10.0.2.2\r\n\r\n' | toybox nc -w 5 10.0.2.2 8000 | head -1
echo "probe_rc=$?"
