#!/usr/bin/env python3
"""Run a LOCAL development command with size-limited rolling stdout/stderr logs."""
import logging
import logging.handlers
import os
import subprocess
import sys

def main():
    if len(sys.argv) < 3:
        print("Usage: lexavault-rolling-runner.py LOG_FILE COMMAND [ARGS...]", file=sys.stderr)
        return 2
    logfile = os.path.abspath(sys.argv[1])
    os.makedirs(os.path.dirname(logfile), exist_ok=True)
    logger = logging.getLogger("lexavault.frontend")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    handler = logging.handlers.RotatingFileHandler(
        logfile, maxBytes=25 * 1024 * 1024, backupCount=14, encoding="utf-8"
    )
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger.addHandler(handler)
    try:
        process = subprocess.Popen(
            sys.argv[2:], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, errors="replace", bufsize=1
        )
        assert process.stdout is not None
        for line in process.stdout:
            logger.info(line.rstrip("\n"))
        return process.wait()
    except Exception:
        logger.exception("Failed to run or capture the frontend process")
        return 1
    finally:
        handler.close()

if __name__ == "__main__":
    sys.exit(main())
