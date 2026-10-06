FROM python:3.12-slim
LABEL org.opencontainers.image.source="https://github.com/basdhaweio/marquee"
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 WEB_DIR=/app/web
WORKDIR /app
COPY server/requirements.txt server/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt
COPY server server
COPY web web
USER 1000:1000
EXPOSE 8890
CMD ["uvicorn", "server.app:app", "--host", "127.0.0.1", "--port", "8890", "--proxy-headers", "--forwarded-allow-ips", "127.0.0.1"]
