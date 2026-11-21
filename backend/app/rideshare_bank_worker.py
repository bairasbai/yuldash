"""Run a bounded read-only bank reconciliation pass."""

import logging
from collections import Counter

import httpx

from .config import settings
from .db import engine
from .rideshare_bank_queue import run_bank_reconciliation
from .rideshare_bank_runtime import configure_rideshare_bank


def main() -> None:
    configuration = configure_rideshare_bank(settings)
    if configuration is None:
        return
    try:
        with httpx.Client(timeout=5.0, follow_redirects=False, trust_env=False) as client:
            results = run_bank_reconciliation(engine, runtime=configuration.runtime,
                                              provider=configuration.provider(client))
        totals = Counter(result.state or "skipped" for result in results)
        logging.getLogger(__name__).info("Rideshare bank reconciliation: %s", dict(totals))
    finally:
        engine.dispose()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    main()
