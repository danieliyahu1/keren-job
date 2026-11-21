#!/bin/sh

# Skills are baked into the image at build time; nothing is rewritten at start.
exec opencode serve --port 4096 --hostname 0.0.0.0
