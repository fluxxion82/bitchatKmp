#!/bin/bash
#
# CardKb I2C Keyboard Driver Installation Script
# For Orange Pi Zero 3 running Armbian
#
# Usage: sudo ./install-cardkb.sh
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/opt/bitchat"
I2C_BUS=2
CARDKB_ADDR=0x5f

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "This script must be run as root (use sudo)"
        exit 1
    fi
}

check_i2c_overlay() {
    log_info "Checking I2C configuration..."

    if [[ ! -f /boot/armbianEnv.txt ]]; then
        log_warn "/boot/armbianEnv.txt not found - may not be Armbian"
        return
    fi

    if grep -q "i2c3-ph" /boot/armbianEnv.txt; then
        log_info "i2c3-ph overlay is configured"
    else
        log_warn "i2c3-ph overlay not found in /boot/armbianEnv.txt"
        log_warn "You may need to add 'i2c3-ph' to the overlays line and reboot"
        log_warn "Example: overlays=i2c3-ph"
    fi
}

check_i2c_device() {
    log_info "Checking for I2C device..."

    if [[ ! -e /dev/i2c-${I2C_BUS} ]]; then
        log_error "/dev/i2c-${I2C_BUS} not found"
        log_error "Enable i2c3-ph overlay in /boot/armbianEnv.txt and reboot"
        exit 1
    fi

    log_info "/dev/i2c-${I2C_BUS} exists"
}

check_cardkb() {
    log_info "Scanning for CardKb at address 0x${CARDKB_ADDR:2}..."

    if ! command -v i2cdetect &> /dev/null; then
        log_warn "i2cdetect not found, skipping CardKb detection"
        return
    fi

    if i2cdetect -y ${I2C_BUS} 2>/dev/null | grep -q "5f"; then
        log_info "CardKb detected at 0x5f"
    else
        log_warn "CardKb not detected at 0x5f"
        log_warn "Check wiring: SDA->Pin3, SCL->Pin5, GND->GND, VCC->3.3V"
    fi
}

install_dependencies() {
    log_info "Installing dependencies..."

    apt-get update -qq
    # Install build tools, Python headers, and I2C tools
    apt-get install -y -qq python3-pip python3-dev python3-smbus build-essential i2c-tools > /dev/null

    # Install Python packages
    # Use --break-system-packages for PEP 668 compliant systems (Debian 12+, Ubuntu 23.04+)
    if pip3 install --help 2>&1 | grep -q "break-system-packages"; then
        pip3 install --break-system-packages python-uinput smbus2
    else
        pip3 install python-uinput smbus2
    fi

    log_info "Dependencies installed"
}

setup_uinput() {
    log_info "Setting up uinput module..."

    # Load module now
    modprobe uinput || true

    # Ensure it loads on boot
    if [[ ! -f /etc/modules-load.d/uinput.conf ]]; then
        echo "uinput" > /etc/modules-load.d/uinput.conf
        log_info "Created /etc/modules-load.d/uinput.conf"
    fi

    log_info "uinput module configured"
}

install_driver() {
    log_info "Installing driver..."

    # Create install directory
    mkdir -p "${INSTALL_DIR}"

    # Copy driver script
    if [[ -f "${SCRIPT_DIR}/cardkb-driver.py" ]]; then
        cp "${SCRIPT_DIR}/cardkb-driver.py" "${INSTALL_DIR}/"
        chmod +x "${INSTALL_DIR}/cardkb-driver.py"
        log_info "Installed cardkb-driver.py to ${INSTALL_DIR}/"
    else
        log_error "cardkb-driver.py not found in ${SCRIPT_DIR}"
        exit 1
    fi
}

install_service() {
    log_info "Installing systemd service..."

    if [[ -f "${SCRIPT_DIR}/cardkb.service" ]]; then
        cp "${SCRIPT_DIR}/cardkb.service" /etc/systemd/system/
        log_info "Installed cardkb.service"
    else
        log_error "cardkb.service not found in ${SCRIPT_DIR}"
        exit 1
    fi

    systemctl daemon-reload
    systemctl enable cardkb
    log_info "Service enabled for auto-start"
}

start_service() {
    log_info "Starting CardKb service..."

    if systemctl start cardkb; then
        log_info "Service started successfully"
        systemctl status cardkb --no-pager
    else
        log_error "Failed to start service"
        log_error "Check logs with: journalctl -u cardkb -f"
        exit 1
    fi
}

show_status() {
    echo ""
    log_info "Installation complete!"
    echo ""
    echo "Useful commands:"
    echo "  sudo systemctl status cardkb    - Check service status"
    echo "  sudo journalctl -u cardkb -f    - View live logs"
    echo "  sudo systemctl restart cardkb   - Restart the driver"
    echo "  sudo evtest                     - Test keyboard input"
    echo ""
    echo "The CardKb should now appear as 'CardKb-I2C' in /dev/input/"
}

# Main installation flow
main() {
    echo "========================================"
    echo "CardKb I2C Keyboard Driver Installer"
    echo "========================================"
    echo ""

    check_root
    check_i2c_overlay
    check_i2c_device
    check_cardkb
    install_dependencies
    setup_uinput
    install_driver
    install_service
    start_service
    show_status
}

# Handle uninstall
if [[ "$1" == "--uninstall" ]]; then
    check_root
    log_info "Uninstalling CardKb driver..."

    systemctl stop cardkb 2>/dev/null || true
    systemctl disable cardkb 2>/dev/null || true
    rm -f /etc/systemd/system/cardkb.service
    rm -f "${INSTALL_DIR}/cardkb-driver.py"
    systemctl daemon-reload

    log_info "CardKb driver uninstalled"
    exit 0
fi

main "$@"
