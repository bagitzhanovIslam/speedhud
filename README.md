# SpeedHUD

**SpeedHUD** is a Minecraft plugin for Spigot/Paper servers that displays the player's movement speed in real time on their screen (HUD). It supports unit switching (m/s or km/h), max speed recording, and a top speed leaderboard.

## Features

- Real-time speed HUD display
- Toggle between meters per second (m/s) and kilometers per hour (km/h)
- Dynamic color coding (green/yellow/red) based on speed
- Max speed recording over a 10-second session
- Leaderboard of recorded top speeds
- Configurable commands through `config.yml`

## Commands

/speedhud on                - Enable the speed HUD
/speedhud off               - Disable the speed HUD
/speedhud unit              - Toggle unit display (m/s or km/h)
/speedhud reload            - Reload the plugin config
/speedhud help              - Show command help
/speedhud startrecordspeed  - Start 10-second max speed recording
/speedhud topspeed          - Show the top speed leaderboard
