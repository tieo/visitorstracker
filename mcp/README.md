# visitorstracker MCP server

An MCP server that reads the online appointment calendars of the Landratsamt
Alb-Donau-Kreis (smartCJM) and the Stadt Ehingen (flexappoint): the same offices the
Android app watches. It only reads; an appointment is booked on the office's own page,
which every answer links.

## Tools

| Tool | Answers |
|---|---|
| `list_offices` | the offices, their authority and booking page |
| `list_services(office_id)` | what a Landratsamt office takes appointments for, with the length and the documents to bring |
| `free_appointments(office_id, service_id?, limit?)` | free times, earliest first, with the number of counters still free |
| `next_free_appointment(office_id, service_id?)` | the earliest free time |

Without a `service_id` an office is asked for its shortest service. Services of one
calendar share the same counters, so those free times cover every longer service too.
Times are local to the offices (Europe/Berlin).

A matter missing from `list_services` cannot be booked online at that office. The
Führerscheinstellen, for example, only offer pickup, replacement and international
licences there; licence applications are sent in without an appointment.

## Running it

```sh
uv run --project mcp visitorstracker-mcp
```

speaks MCP over stdio. To add it to Claude Code:

```sh
claude mcp add --scope user visitorstracker -- uv run --project /path/to/visitorstracker/mcp visitorstracker-mcp
```

## Being gentle with the calendars

The smartCJM calendars sit behind a rate limit that blocks an address for about five
minutes after roughly 60 requests a minute. The server leaves at least 1.5 seconds
between two requests to the same host and keeps every answer for five minutes. A
Landratsamt calendar takes up to five requests to read, a Stadt Ehingen calendar one
per day up to 60 days ahead (`next_free_appointment` stops at the first day with a free
time).
