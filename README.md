# BYOWWebServer

I couldn't figure out how to make a web client for BYOWServer, so I made this instead.

Performance is quite bad, but its marginally playable.


## Usage

Copy BYOWWebServer.java into the Networking directory, then change Engine.java to use BYOWWebServer instead of BYOWServer.

Run `ngrok http [port]` instead of `ngrok tcp [port]`. Then run the game, which should start up a webserver that says "waiting for connection".

Then you (or a friend), can go to <https://sberkun.github.io/BYOWWebServer/>, enter the ngrok url, and play the game!
