#!/usr/bin/env python3
"""Leitor contínuo HX711 (BCM). Imprime linhas: W <kg> <stable 0|1> <raw>"""
from __future__ import annotations

import argparse
import statistics
import sys
import time


def _open_gpio(dout: int, sck: int):
    try:
        import lgpio  # type: ignore

        handle = lgpio.gpiochip_open(0)
        lgpio.gpio_claim_input(handle, dout)
        lgpio.gpio_claim_output(handle, sck, 0)

        class LgpioBackend:
            def read_dout(self) -> int:
                return 1 if lgpio.gpio_read(handle, dout) else 0

            def write_sck(self, level: int) -> None:
                lgpio.gpio_write(handle, sck, 1 if level else 0)

            def close(self) -> None:
                try:
                    lgpio.gpiochip_close(handle)
                except Exception:
                    pass

        return LgpioBackend()
    except Exception:
        pass

    try:
        import RPi.GPIO as GPIO  # type: ignore

        GPIO.setwarnings(False)
        GPIO.setmode(GPIO.BCM)
        GPIO.setup(dout, GPIO.IN)
        GPIO.setup(sck, GPIO.OUT, initial=GPIO.LOW)

        class RpiBackend:
            def read_dout(self) -> int:
                return 1 if GPIO.input(dout) else 0

            def write_sck(self, level: int) -> None:
                GPIO.output(sck, GPIO.HIGH if level else GPIO.LOW)

            def close(self) -> None:
                try:
                    GPIO.cleanup((dout, sck))
                except Exception:
                    pass

        return RpiBackend()
    except Exception as exc:
        raise RuntimeError(
            "Nenhum backend GPIO disponível (lgpio ou RPi.GPIO). Detalhe: " + str(exc)
        ) from exc


def _wait_ready(gpio, timeout_s: float = 1.0) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if gpio.read_dout() == 0:
            return True
        time.sleep(0.001)
    return False


def _read_raw(gpio):
    if not _wait_ready(gpio):
        return None
    value = 0
    for _ in range(24):
        gpio.write_sck(1)
        value = (value << 1) | gpio.read_dout()
        gpio.write_sck(0)
    # 1 ciclo extra = ganho 128, canal A
    gpio.write_sck(1)
    gpio.write_sck(0)
    if value & 0x800000:
        value |= ~0xFFFFFF
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="HX711 continuous reader")
    parser.add_argument("--dout", type=int, default=5)
    parser.add_argument("--sck", type=int, default=6)
    parser.add_argument("--ref-unit", type=float, default=1.0,
                        help="Divisor (raw - offset) / ref_unit => kg")
    parser.add_argument("--offset", type=float, default=None,
                        help="Offset; se omitido, tara com as primeiras amostras")
    parser.add_argument("--samples", type=int, default=8)
    parser.add_argument("--interval-ms", type=int, default=120)
    parser.add_argument("--stable-stdev", type=float, default=80.0)
    args = parser.parse_args()

    if args.ref_unit == 0:
        print("ERR ref-unit não pode ser 0", file=sys.stderr, flush=True)
        return 2

    try:
        gpio = _open_gpio(args.dout, args.sck)
    except Exception as exc:
        print("ERR " + str(exc), file=sys.stderr, flush=True)
        return 1

    offset = args.offset
    window: list[float] = []
    try:
        # Descarta primeiras leituras instáveis do chip
        for _ in range(5):
            _read_raw(gpio)
            time.sleep(0.05)

        if offset is None:
            tare: list[float] = []
            for _ in range(max(8, args.samples)):
                raw = _read_raw(gpio)
                if raw is not None:
                    tare.append(float(raw))
                time.sleep(0.02)
            if not tare:
                print("ERR HX711 sem resposta (DOUT sempre HIGH?)", file=sys.stderr, flush=True)
                return 1
            offset = statistics.mean(tare)
            print(f"INFO tare_offset={offset:.1f}", file=sys.stderr, flush=True)

        while True:
            raw = _read_raw(gpio)
            if raw is None:
                time.sleep(args.interval_ms / 1000.0)
                continue
            window.append(float(raw))
            if len(window) > args.samples:
                window.pop(0)
            avg = statistics.mean(window)
            kg = (avg - offset) / args.ref_unit
            stable = 0
            if len(window) >= max(3, args.samples // 2):
                stdev = statistics.pstdev(window) if len(window) > 1 else 9999.0
                if stdev <= args.stable_stdev:
                    stable = 1
            print(f"W {kg:.6f} {stable} {int(avg)}", flush=True)
            time.sleep(max(0.02, args.interval_ms / 1000.0))
    except KeyboardInterrupt:
        return 0
    finally:
        try:
            gpio.close()
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
