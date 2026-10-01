# Captain-Bot (Mattermost <-> opencode). Gebaut ueber das Root-compose.yml.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app
COPY pyproject.toml README.md ./
COPY captain ./captain
RUN pip install .

# Laeuft bewusst als root: opencode (ebenfalls root) und der Bot teilen das
# Volume /tmp/captain; der Bot legt dort die Session-Verzeichnisse an.
ENV SESSIONS_DIR=/tmp/captain \
    DATA_DIR=/data
VOLUME ["/data"]

CMD ["python", "-m", "captain"]
