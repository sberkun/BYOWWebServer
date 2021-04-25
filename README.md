# BYOWWebServer

A simple server and web client for BYOW.

## Usage

Copy BYOWWebServer.java into the Networking directory, then change Engine.java to use BYOWWebServer instead of BYOWServer.

Run `ngrok http [port]` instead of `ngrok tcp [port]`. Then run the game, which should start up a webserver that says "waiting for connection".

Then you (or a friend), can go to <https://sberkun.github.io/BYOWWebServer/>, enter the ngrok url, and play the game!

## Performance

This implementation only sends the differences between frames, rather than entire frames. For an 80x50 world, this means it usually sends around 5kb rather than 110kb. 

In ad-hoc testing, latency was found to average around 300ms. This is bad, but better than the existing BYOWServer which ranged between 400-600ms. The latency breakdown for the web client is roughly as follows:
 - 80 ms to send a keypress to server (ngrok is probably making this bad)
 - 30 ms for the game to calculate and render the next frame
 - 30 ms for StdDraw to save the frame to a png file
 - 70 ms to calculate the difference from the last frame and convert it to png format
 - 90 ms to send the resulting frame back to the client. This is upped to 300-400ms if it's a full image (around 100kb) rather than a difference of frames.
