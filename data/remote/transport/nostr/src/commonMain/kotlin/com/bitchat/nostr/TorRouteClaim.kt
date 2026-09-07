package com.bitchat.nostr

/**
 * Whether a relay socket may be reported to the user as having travelled through Tor.
 *
 * [NostrRelay] cannot see the route the HTTP engine actually took: the engine's
 * `ProxySelector.select()` (`ktorHttpClient.jvm.kt`) runs when the socket is opened, which is after
 * the connect call is made and before - or at - the moment the open is reported back. So there are
 * two observations available, and neither is the truth on its own:
 *
 * - [viaTorAtConnect] alone **over-claims**: if Tor drops inside that window the selector answers
 *   `NO_PROXY`, the socket leaves directly, and the line still says "Tor connection established".
 * - [routingThroughTorNow] alone **over-claims** the other way: if Tor comes up inside the window,
 *   a socket the selector had already sent direct is reported as Tor.
 *
 * Requiring both rules out every single transition in the window. What is left is under-claiming -
 * a direct-looking line for a socket that really did go through Tor - which is safe: the user is
 * never told they are protected when they are not.
 *
 * **Residual window.** Tor flapping twice between the two reads (off and back on, or on and back
 * off) is still reported from the endpoints and can therefore still be wrong. Closing that needs
 * the proxy the selector actually chose, which only the engine knows: on JVM that is an OkHttp
 * `EventListener.connectStart(call, inetSocketAddress, proxy)` or `Connection.route().proxy()`,
 * surfaced through `NostrWebSocketListener` and implemented per platform. Until that plumbing
 * exists, this is the freshest evidence [NostrRelay] has.
 */
internal fun claimsTorRoute(viaTorAtConnect: Boolean, routingThroughTorNow: Boolean): Boolean =
    viaTorAtConnect && routingThroughTorNow
