#!/bin/bash
#
# GPIO Discovery Script for Orange Pi Zero 3
#
# Run this on the Orange Pi to discover correct GPIO mappings
# for the RFM95W LoRa module.
#
# Usage: ./gpio_discover.sh
#

echo "========================================"
echo "Orange Pi Zero 3 GPIO Discovery"
echo "========================================"
echo

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo "[WARN] Not running as root - some info may be unavailable"
    echo "       Run with: sudo ./gpio_discover.sh"
    echo
fi

echo "--- Method 1: gpioinfo (libgpiod) ---"
if command -v gpioinfo &> /dev/null; then
    gpioinfo 2>/dev/null || echo "[INFO] gpioinfo failed or no permissions"
else
    echo "[INFO] gpioinfo not installed"
    echo "       Install with: sudo apt install gpiod libgpiod-utils"
fi
echo

echo "--- Method 2: GPIO debug interface ---"
if [ -f /sys/kernel/debug/gpio ]; then
    cat /sys/kernel/debug/gpio 2>/dev/null || echo "[INFO] Cannot read - need root"
else
    echo "[INFO] GPIO debug interface not available"
fi
echo

echo "--- Method 3: SPI device check ---"
echo "Available SPI devices:"
ls -la /dev/spidev* 2>/dev/null || echo "[WARN] No SPI devices found!"
echo

echo "--- Method 4: Device tree overlays ---"
if [ -f /boot/orangepiEnv.txt ]; then
    echo "Contents of /boot/orangepiEnv.txt:"
    cat /boot/orangepiEnv.txt
else
    echo "[INFO] /boot/orangepiEnv.txt not found"
fi
echo

if [ -f /boot/armbianEnv.txt ]; then
    echo "Contents of /boot/armbianEnv.txt:"
    cat /boot/armbianEnv.txt
fi
echo

echo "--- Method 5: GPIO sysfs exports ---"
echo "Currently exported GPIOs:"
ls /sys/class/gpio/ 2>/dev/null | grep -E "gpio[0-9]+" || echo "[INFO] None exported"
echo

echo "--- Method 6: Check GPIO chip info ---"
echo "GPIO chips in /sys/class/gpio/:"
for chip in /sys/class/gpio/gpiochip*; do
    if [ -d "$chip" ]; then
        name=$(basename $chip)
        base=$(cat $chip/base 2>/dev/null || echo "?")
        ngpio=$(cat $chip/ngpio 2>/dev/null || echo "?")
        label=$(cat $chip/label 2>/dev/null || echo "unknown")
        echo "  $name: base=$base, count=$ngpio, label=$label"
    fi
done
echo

echo "--- GPIO Calculation for Orange Pi Zero 3 ---"
echo "Allwinner H618 GPIO formula:"
echo "  GPIO_NUMBER = (Port_Base) + Pin_Number"
echo ""
echo "Port bases (typical):"
echo "  PA = 0    (PA0-PA31)"
echo "  PB = 32   (PB0-PB31)"
echo "  PC = 64   (PC0-PC31)"
echo "  PD = 96   (PD0-PD31)"
echo "  PE = 128  (PE0-PE31)"
echo "  PF = 160  (PF0-PF31)"
echo "  PG = 192  (PG0-PG31)"
echo "  PH = 224  (PH0-PH31)"
echo "  PI = 256  (PI0-PI31)"
echo ""
echo "For RFM95W on Orange Pi Zero 3:"
echo "  Pin 7 (RESET) = PC10 = 64 + 10 = 74"
echo "  Pin 11 (DIO0) = PC11 = 64 + 11 = 75"
echo ""
echo "[INFO] These are estimates - verify with gpioinfo!"
echo

echo "--- Quick GPIO Test ---"
echo "To test a GPIO pin (replace 74 with your pin):"
echo ""
echo "  # Export the pin"
echo "  echo 74 > /sys/class/gpio/export"
echo ""
echo "  # Set as output"
echo "  echo out > /sys/class/gpio/gpio74/direction"
echo ""
echo "  # Toggle (check with multimeter/LED)"
echo "  echo 1 > /sys/class/gpio/gpio74/value"
echo "  echo 0 > /sys/class/gpio/gpio74/value"
echo ""
echo "  # Unexport when done"
echo "  echo 74 > /sys/class/gpio/unexport"
echo

echo "========================================"
echo "Discovery complete"
echo "========================================"
