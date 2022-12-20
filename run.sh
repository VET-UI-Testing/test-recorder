#!/bin/bash

cd "$(dirname "$0")"
echo "Starting TestRecorder"
java -jar TestRecorder.jar \
  "${OUT_DIR}" "${SCREEN_OUT_DIR}" \
  > >(ts "%Y/%m/%d %H:%M:%.S" >"${OUT_DIR}/cap.log") 2>&1 &
pid=$!
trap 'kill ${pid}' INT
wait "$pid"

echo "TestRecorder stopped"

if [ "$1" == "cleanup" ]; then
	if [ ! -z "$MINICAP_PORT" ]; then
		adb forward --remove "tcp:$MINICAP_PORT"
	fi
	if [ ! -z "$CTRL_PORT" ]; then
		adb forward --remove "tcp:$CTRL_PORT"
	fi
fi
