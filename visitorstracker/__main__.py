import argparse
import logging
import tomllib
from importlib import resources
from pathlib import Path

from . import collect, store


def load_offices(path: Path | None) -> list[dict]:
    if path:
        text = path.read_text(encoding="utf-8")
    else:
        text = resources.files(__package__).joinpath("offices.toml").read_text(encoding="utf-8")
    return tomllib.loads(text)["office"]


def main():
    parser = argparse.ArgumentParser(prog="visitorstracker")
    parser.add_argument("--db", type=Path, required=True, help="SQLite database file")
    parser.add_argument("--offices", type=Path, help="offices.toml replacing the bundled one")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("collect", help="poll every office once")
    serve = commands.add_parser("serve", help="run the dashboard")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8093)
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    offices = load_offices(args.offices)
    db = store.connect(args.db)
    if args.command == "collect":
        collect.run(db, offices)
    else:
        from . import web

        web.serve(args.db, offices, args.host, args.port)


if __name__ == "__main__":
    main()
