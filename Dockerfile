# ==============================================================================
# OpenCode — headless server
#
# Runs `opencode serve` on port 4096.
# Includes Playwright MCP for browser automation (linkedin-jobs skill).
# ==============================================================================
FROM node:lts-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    xauth dbus-x11 \
    gettext-base \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g opencode-ai

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN npx -y @playwright/mcp@latest --version \
    && npx -y playwright install --with-deps chromium

RUN groupadd -r opencode && useradd -r -g opencode -d /home/opencode opencode \
    && mkdir -p /home/opencode/.config/opencode \
    && mkdir -p /home/opencode/.playwright-mcp \
    && mkdir -p /home/opencode/.playwright-mcp-output \
    && chown -R opencode:opencode /home/opencode \
    && chown -R opencode:opencode /ms-playwright

COPY agent/opencode.json /home/opencode/.config/opencode/opencode.json
RUN chown opencode:opencode /home/opencode/.config/opencode/opencode.json

COPY agent/.opencode/skills/ /home/opencode/.config/opencode/skills/
RUN chown -R opencode:opencode /home/opencode/.config/opencode/skills/

RUN mkdir -p /home/opencode/workspace \
    && chown opencode:opencode /home/opencode/workspace

COPY agent/opencode.json /home/opencode/workspace/opencode.json
COPY agent/.opencode/ /home/opencode/workspace/.opencode/
RUN chown -R opencode:opencode /home/opencode/workspace

COPY opencode-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

USER opencode
WORKDIR /home/opencode/workspace

EXPOSE 4096

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
