FROM python:3.12-slim AS base

ENV POETRY_NO_INTERACTION=1 \
    POETRY_VIRTUALENVS_CREATE=false \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN pip install --no-cache-dir poetry==1.8.3

# Leverage layer caching: resolve dependencies before copying source
COPY pyproject.toml ./
RUN poetry install --no-root --only main

COPY app ./app

RUN adduser --system --group trace
USER trace

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
