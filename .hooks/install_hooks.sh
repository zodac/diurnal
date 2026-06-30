#!/bin/bash
# ------------------------------------------------------------------
# Git hooks installer: installs all .sh scripts in this folder as hooks
# ------------------------------------------------------------------

# Get the directory where this script is located
HOOKS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Iterate over all .sh files in the hooks directory, except this installer
for script in "${HOOKS_DIR}"/*.sh; do
    [ "${script}" = "${HOOKS_DIR}/$(basename "${0}")" ] && continue  # skip this installer
    hook_name=$(basename "${script}" .sh)  # remove .sh extension for Git hook name
    
    echo "Installing hook: ${hook_name}"
    cp "$script" ".git/hooks/${hook_name}"
    chmod +x ".git/hooks/${hook_name}"
done

echo "All Git hooks installed successfully!"
