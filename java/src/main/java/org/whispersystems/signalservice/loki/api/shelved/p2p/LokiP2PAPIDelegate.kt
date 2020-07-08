package org.whispersystems.signalservice.loki.api.shelved.rssfeeds.p2p

interface LokiP2PAPIDelegate {

    fun ping(contactHexEncodedPublicKey: String)
}
