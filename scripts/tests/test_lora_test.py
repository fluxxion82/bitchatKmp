"""Exercise the real diagnostic entry point without SPI/GPIO hardware."""
import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("lora_test", Path(__file__).parents[1] / "lora_test.py")
lora = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lora)


class FakeSPI:
    def __init__(self, versions=None, ignored_register=None, fail_read=False):
        self.versions = iter(versions or [0x12] * 20)
        self.ignored_register = ignored_register
        self.fail_read = fail_read
        self.registers = {lora.REG_OP_MODE: 1}
        self.transfers = []
        self.opened = None
        self.closed = False
        self.transmitted = False

    def open(self, bus, device):
        self.opened = (bus, device)

    def close(self):
        self.closed = True

    def xfer2(self, data):
        self.transfers.append(data[:])
        reg = data[0] & 0x7f
        if data[0] & 0x80:
            if reg != self.ignored_register:
                if reg == lora.REG_IRQ_FLAGS:
                    self.registers[reg] = self.registers.get(reg, 0) & ~data[1]
                else:
                    self.registers[reg] = data[1]
                if reg == lora.REG_OP_MODE and data[1] == lora.MODE_LORA | lora.MODE_TX:
                    self.transmitted = True
                    self.registers[lora.REG_IRQ_FLAGS] = lora.IRQ_TX_DONE
            return [0, 0]
        if self.fail_read:
            raise OSError("SPI transfer failed")
        return [0, next(self.versions, 0x12) if reg == lora.REG_VERSION else self.registers.get(reg, 0)]

    @property
    def writes(self):
        return [transfer for transfer in self.transfers if transfer[0] & 0x80]


class LoRaDiagnosticTest(unittest.TestCase):
    def run_cli(self, spi, *args):
        gpio_calls = []
        gpio = types.ModuleType("RPi.GPIO")
        gpio.BCM, gpio.OUT, gpio.HIGH, gpio.LOW = 0, 1, 1, 0
        for name in ("setmode", "setwarnings", "setup", "output", "cleanup"):
            setattr(gpio, name, lambda *a: gpio_calls.append(a))
        modules = {"spidev": types.SimpleNamespace(SpiDev=lambda: spi), "RPi": types.ModuleType("RPi"), "RPi.GPIO": gpio}
        with patch.dict(sys.modules, modules), patch.object(sys, "argv", ["lora_test.py", *args]), patch("os.path.exists", return_value=True), patch("os.access", return_value=True), patch.object(lora.time, "sleep"), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            try:
                result = lora.main()
            except SystemExit as error:
                result = error.code
        return result, gpio_calls

    def test_default_is_read_only_on_spi1_1_without_gpio(self):
        spi = FakeSPI()
        result, gpio_calls = self.run_cli(spi)
        self.assertEqual(result, 0)
        self.assertEqual(spi.opened, (1, 1))
        self.assertEqual(gpio_calls, [])
        self.assertEqual(spi.writes, [])
        self.assertTrue(spi.closed)
        self.assertGreaterEqual(sum(t[0] == lora.REG_VERSION for t in spi.transfers), 10)

    def test_constructor_defaults_to_current_bus(self):
        self.assertEqual(lora.LoRaTest().spi_device, "/dev/spidev1.1")

    def test_dump_does_not_configure_or_write_during_cleanup(self):
        spi = FakeSPI()
        result, gpio_calls = self.run_cli(spi, "--dump", "--skip-gpio")
        self.assertEqual(result, 0)
        self.assertEqual(spi.writes, [])
        self.assertEqual(gpio_calls, [])
        self.assertIn([lora.REG_FRF_MSB, 0], spi.transfers)

    def test_failed_or_inconsistent_identity_never_writes(self):
        for versions in ([0] * 10, [255] * 10, [0x22] * 10, [0x12] * 9 + [0]):
            for args in ((), ("--configure",), ("--transmit",), ("--dump",)):
                with self.subTest(versions=versions, args=args):
                    spi = FakeSPI(versions)
                    result, _ = self.run_cli(spi, *args)
                    self.assertEqual(result, 1)
                    self.assertEqual(spi.writes, [])
                    self.assertFalse(spi.transmitted)
                    self.assertTrue(spi.closed)

    def test_explicit_configuration_writes_without_transmitting(self):
        spi = FakeSPI()
        result, gpio_calls = self.run_cli(spi, "--configure")
        self.assertEqual(result, 0)
        self.assertTrue(spi.writes)
        self.assertFalse(spi.transmitted)
        self.assertEqual(gpio_calls, [])
        self.assertEqual(spi.registers[lora.REG_PA_DAC], 0x84)  # +17 dBm, no hidden +20 dBm mode

    def test_bad_configuration_readback_prevents_transmission(self):
        for register in (lora.REG_FRF_MSB, lora.REG_MODEM_CONFIG_1, lora.REG_PA_CONFIG):
            with self.subTest(register=register):
                spi = FakeSPI(ignored_register=register)
                result, _ = self.run_cli(spi, "--transmit")
                self.assertEqual(result, 1)
                self.assertFalse(spi.transmitted)
                self.assertTrue(spi.closed)

    def test_transmit_is_explicit_and_configures_first(self):
        spi = FakeSPI()
        result, _ = self.run_cli(spi, "--transmit")
        self.assertEqual(result, 0)
        self.assertTrue(spi.transmitted)
        self.assertEqual(spi.registers[lora.REG_SYNC_WORD], 0x12)

    def test_read_failure_closes_spi_and_returns_failure(self):
        spi = FakeSPI(fail_read=True)
        result, _ = self.run_cli(spi)
        self.assertEqual(result, 1)
        self.assertTrue(spi.closed)
        self.assertEqual(spi.writes, [])

    def test_gpio_reset_requests_are_rejected_before_open(self):
        for args in (("--gpio-test",), ("--reset-pin", "73"), ("--reset-pin", "71")):
            with self.subTest(args=args):
                spi = FakeSPI()
                result, gpio_calls = self.run_cli(spi, *args)
                self.assertEqual(result, 2)
                self.assertIsNone(spi.opened)
                self.assertEqual(gpio_calls, [])


if __name__ == "__main__":
    unittest.main()
