# BYOWWebServer

A simple server and web client for BYOW. Performance exceeds the performance of BYOWServer.java for large worlds and/or low bandwidth.

## Usage

Copy BYOWWebServer.java into the Networking directory, then change Engine.java to use BYOWWebServer instead of BYOWServer.

Run `ngrok http [port]` instead of `ngrok tcp [port]`. Then run the game, which should start up a webserver that says "waiting for connection".

Then you (or a friend), can go to <https://sberkun.github.io/BYOWWebServer/>, enter the ngrok url, and play the game!
