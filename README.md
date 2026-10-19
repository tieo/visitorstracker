# Behörden-Termin-Tracker Ulm/Ehingen

Free appointments at the offices of the Landratsamt Alb-Donau-Kreis (vehicle
registration, driving licences, foreigners' office) and the Stadt Ehingen (Bürgerbüro,
foreigners' office, pensions office), read straight from their online booking calendars.

- [`android/`](android): the app. It keeps the history of every calendar on the phone,
  learns when each office releases new appointments and which weekday and hour are
  easiest to get, alerts when a free time turns up and follows walk-in tickets. All
  fetching happens in the app; there is no server.
- [`mcp/`](mcp): an MCP server so an assistant can look up the offices, what they take
  appointments for and the next free time.

Both only read the calendars. Nothing is ever reserved.
