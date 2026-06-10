#!/bin/sh

# Inject credentials into skill files from environment variables
find /home/opencode/.config/opencode/skills -name "*.md" | while read f; do
  envsubst '${LINKEDIN_EMAIL}${LINKEDIN_PASSWORD}' < "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done

exec opencode serve --port 4096 --hostname 0.0.0.0
