#!/bin/bash
set -e

# Local development entrypoint for Keycloak.
# Dev mode: embedded H2 store, HTTP only, no strict hostname checks.
exec /opt/keycloak/bin/kc.sh start-dev
