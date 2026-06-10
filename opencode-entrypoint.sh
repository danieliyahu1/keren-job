#!/bin/sh

# Inject credentials into skill files from environment variables
for skills_dir in /home/opencode/.config/opencode/skills /home/opencode/workspace/.opencode/skills; do
  find "$skills_dir" -name "*.md" | while read f; do
    envsubst '${LINKEDIN_EMAIL}${LINKEDIN_PASSWORD}' < "$f" > "$f.tmp" && mv "$f.tmp" "$f"
  done
done

exec opencode serve --port ${PORT:-4096} --hostname 0.0.0.0
