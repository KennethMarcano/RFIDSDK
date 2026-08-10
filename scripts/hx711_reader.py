#!/usr/bin/env python3
"""Leitor contínuo HX711 (BCM). Imprime linhas: W <kg> <stable 0|1> <raw_filtrado>"""
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


def _robust_center(window: list[float]) -> float:
    """Mediana + média aparada: ignora picos extremos."""
    if len(window) == 1:
        return window[0]
    med = statistics.median(window)
    # Descarta outliers longe da mediana (picos de ruído)
    span = max(80.0, 2.5 * (statistics.pstdev(window) if len(window) > 1 else 80.0))
    kept = [v for v in window if abs(v - med) <= span]
    if len(kept) < max(3, len(window) // 3):
        kept = list(window)
    if len(kept) >= 5:
        ordered = sorted(kept)
        trim = max(1, len(ordered) // 5)
        ordered = ordered[trim:-trim] if trim * 2 < len(ordered) else ordered
        return statistics.mean(ordered)
    return statistics.median(kept)


def main() -> int:
    parser = argparse.ArgumentParser(description="HX711 continuous reader (filtrado)")
    parser.add_argument("--dout", type=int, default=5)
    parser.add_argument("--sck", type=int, default=6)
    parser.add_argument("--ref-unit", type=float, default=1.0,
                        help="Divisor (raw - offset) / ref_unit => kg")
    parser.add_argument("--offset", type=float, default=None,
                        help="Offset; se omitido, tara com as primeiras amostras")
    parser.add_argument("--samples", type=int, default=24,
                        help="Janela de amostras para mediana/média (maior = mais estável)")
    parser.add_argument("--interval-ms", type=int, default=60)
    parser.add_argument("--stable-stdev", type=float, default=220.0,
                        help="pstdev da janela abaixo deste valor => stable=1")
    parser.add_argument("--ema", type=float, default=0.18,
                        help="Peso do EMA (0.05=muito suave, 0.4=rápido). 0 desliga.")
    parser.add_argument("--deadband-raw", type=float, default=120.0,
                        help="Só publica mudança se |delta raw| >= isto (0 desliga)")
    parser.add_argument("--quantize-g", type=float, default=2.0,
                        help="Arredonda o kg exibido para múltiplos desta gramatura (0 desliga)")
    args = parser.parse_args()

    if args.ref_unit == 0:
        print("ERR ref-unit não pode ser 0", file=sys.stderr, flush=True)
        return 2
    if args.samples < 3:
        print("ERR --samples deve ser >= 3", file=sys.stderr, flush=True)
        return 2

    try:
        gpio = _open_gpio(args.dout, args.sck)
    except Exception as exc:
        print("ERR " + str(exc), file=sys.stderr, flush=True)
        return 1

    offset = args.offset
    window: list[float] = []
    ema_raw = None
    published_raw = None
    try:
        # Descarta primeiras leituras instáveis do chip
        for _ in range(8):
            _read_raw(gpio)
            time.sleep(0.03)

        if offset is None:
            tare: list[float] = []
            for _ in range(max(16, args.samples)):
                raw = _read_raw(gpio)
                if raw is not None:
                    tare.append(float(raw))
                time.sleep(0.02)
            if not tare:
                print("ERR HX711 sem resposta (DOUT sempre HIGH?)", file=sys.stderr, flush=True)
                return 1
            offset = _robust_center(tare)
            print(f"INFO tare_offset={offset:.1f}", file=sys.stderr, flush=True)

        while True:
            raw = _read_raw(gpio)
            if raw is None:
                time.sleep(args.interval_ms / 1000.0)
                continue

            window.append(float(raw))
            if len(window) > args.samples:
                window.pop(0)

            if len(window) < max(5, args.samples // 3):
                time.sleep(max(0.02, args.interval_ms / 1000.0))
                continue

            center = _robust_center(window)

            # EMA para suavizar oscilação residual
            if args.ema > 0:
                alpha = min(1.0, max(0.01, args.ema))
                if ema_raw is None:
                    ema_raw = center
                else:
                    ema_raw = (alpha * center) + ((1.0 - alpha) * ema_raw)
                filtered = ema_raw
            else:
                filtered = center

            # Deadband: evita tremer o display com ruído residual
            if published_raw is None:
                published_raw = filtered
            elif args.deadband_raw > 0 and abs(filtered - published_raw) < args.deadband_raw:
                filtered_out = published_raw
            else:
                published_raw = filtered
                filtered_out = filtered

            kg = (filtered_out - offset) / args.ref_unit
            if args.quantize_g > 0:
                step_kg = args.quantize_g / 1000.0
                kg = round(kg / step_kg) * step_kg

            stable = 0
            if len(window) >= max(5, args.samples // 2):
                stdev = statistics.pstdev(window) if len(window) > 1 else 9999.0
                # Também exige que o EMA esteja perto do centro (sem tendência forte)
                drift = abs(filtered - center) if ema_raw is not None else 0.0
                if stdev <= args.stable_stdev and drift <= args.stable_stdev:
                    stable = 1

            print(f"W {kg:.6f} {stable} {int(round(filtered_out))}", flush=True)
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
