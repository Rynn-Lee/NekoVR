#!/usr/bin/env bash

# Change to the project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=================================================="
echo "         Starting NekoVR / SlimeVR"
echo "=================================================="

# Function to clean up background processes on exit
cleanup() {
    echo ""
    echo "Stopping all services..."
    kill $(jobs -p) 2>/dev/null || true
    wait $(jobs -p) 2>/dev/null || true
    echo "Stopped."
}
trap cleanup SIGINT SIGTERM EXIT

# 1. Start Java Backend Server
echo "[1/2] Starting backend server..."
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" || "$OSTYPE" == "cygwin" ]]; then
    ./gradlew.bat :server:desktop:run &
else
    ./gradlew :server:desktop:run &
fi
SERVER_PID=$!

# 2. Start Web GUI (Vite)
echo "[2/2] Starting web GUI..."
(cd gui && pnpm start) &
GUI_PID=$!

echo ""
echo "=================================================="
echo "  Web Interface:   http://localhost:5173/"
echo "  Backend Server:  ws://localhost:21110"
echo "  Press Ctrl+C to stop all services"
echo "=================================================="
echo ""

# Wait for both processes
wait
