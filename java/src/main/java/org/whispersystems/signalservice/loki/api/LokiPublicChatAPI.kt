package org.whispersystems.signalservice.loki.api

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.map
import nl.komponents.kovenant.then
import org.whispersystems.libsignal.logging.Log
import org.whispersystems.signalservice.internal.util.Hex
import org.whispersystems.signalservice.internal.util.JsonUtil
import org.whispersystems.signalservice.loki.messaging.LokiUserDatabaseProtocol
import org.whispersystems.signalservice.loki.utilities.Analytics
import org.whispersystems.signalservice.loki.utilities.retryIfNeeded
import java.text.SimpleDateFormat
import java.util.*

class LokiPublicChatAPI(private val userHexEncodedPublicKey: String, private val userPrivateKey: ByteArray, private val apiDatabase: LokiAPIDatabaseProtocol, private val userDatabase: LokiUserDatabaseProtocol) : LokiDotNetAPI(userHexEncodedPublicKey, userPrivateKey, apiDatabase) {

    companion object {
        private val moderators: HashMap<String, HashMap<Long, Set<String>>> = hashMapOf() // Server URL to (channel ID to set of moderator IDs)

        // region Settings
        private val fallbackBatchCount = 256
        private val maxRetryCount = 8
        // endregion

        // region Public Chat
        private val channelInfoType = "net.patter-app.settings"
        private val attachmentType = "net.app.core.oembed"
        @JvmStatic
        public val publicChatMessageType = "network.loki.messenger.publicChat"

        fun getDefaultChats(isDebug: Boolean = false): List<LokiPublicChat> {
            val result = mutableListOf<LokiPublicChat>()
            result.add(LokiPublicChat(1, "https://chat.lokinet.org", "Loki Public Chat", true))
            if (isDebug) {
                result.add(LokiPublicChat(1, "https://chat-dev.lokinet.org", "Loki Dev Chat", true))
            }
            return result
        }
        // endregion

        // region Convenience
        public fun isUserModerator(hexEncodedPublicKey: String, channel: Long, server: String): Boolean {
            if (moderators[server] != null && moderators[server]!![channel] != null) {
                return moderators[server]!![channel]!!.contains(hexEncodedPublicKey)
            }
            return false
        }
        // endregion
    }

    // region Public API
    public fun getMessages(channel: Long, server: String): Promise<List<LokiPublicChatMessage>, Exception> {
        Log.d("Loki", "Getting messages for public chat channel with ID: $channel on server: $server.")
        val parameters = mutableMapOf<String, Any>("include_annotations" to 1)
        val lastMessageServerID = apiDatabase.getLastMessageServerID(channel, server)
        if (lastMessageServerID != null) {
            parameters["since_id"] = lastMessageServerID
        } else {
            parameters["count"] = fallbackBatchCount
        }
        return execute(HTTPVerb.GET, server, "channels/$channel/messages", parameters).then { response ->
            try {
                val bodyAsString = response.body()!!.string()
                val body = JsonUtil.fromJson(bodyAsString)
                val data = body.get("data")
                val messages = data.mapNotNull { message ->
                    try {
                        val isDeleted = message.has("is_deleted") && message.get("is_deleted").asBoolean(false)
                        if (isDeleted) { return@mapNotNull null }
                        // Ignore messages without annotations
                        if (!message.hasNonNull("annotations")) { return@mapNotNull null }
                        val annotation = message.get("annotations").find {
                            (it.get("type").asText("") == publicChatMessageType) && it.hasNonNull("value")
                        } ?: return@mapNotNull null
                        val value = annotation.get("value")
                        val serverID = message.get("id").asLong()
                        val user = message.get("user")
                        val hexEncodedPublicKey = user.get("username").asText()
                        val displayName = if (user.hasNonNull("name")) user.get("name").asText() else "Anonymous"
                        @Suppress("NAME_SHADOWING") val body = message.get("text").asText()
                        val timestamp = value.get("timestamp").asLong()
                        var quote: LokiPublicChatMessage.Quote? = null
                        if (value.hasNonNull("quote")) {
                            val replyTo = if (message.hasNonNull("reply_to")) message.get("reply_to").asLong() else null
                            val quoteAnnotation = value.get("quote")
                            val quoteTimestamp = quoteAnnotation.get("id").asLong()
                            val author = quoteAnnotation.get("author").asText()
                            val text = quoteAnnotation.get("text").asText()
                            quote = if (quoteTimestamp > 0L && author != null && text != null) LokiPublicChatMessage.Quote(quoteTimestamp, author, text, replyTo) else null
                        }
                        val attachmentNodes = message.get("annotations").filter { (it.get("type").asText("") == attachmentType) && it.hasNonNull("value") }
                        val attachments = attachmentNodes.map { it.get("value") }.mapNotNull { node ->
                            try {
                                val id = node.get("id").asLong()
                                val contentType = node.get("contentType").asText()
                                val size = node.get("size").asInt()
                                val fileName = node.get("fileName").asText()
                                val flags = node.get("flags").asInt()
                                val width = node.get("width").asInt()
                                val height = node.get("height").asInt()
                                val url = node.get("url").asText()
                                val caption = if (node.hasNonNull("caption")) node.get("caption").asText() else null
                                LokiPublicChatMessage.Attachment(server, id, contentType, size, fileName, flags, width, height, caption, url)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        // Set the last message server ID here to avoid the situation where a message doesn't have a valid signature and this function is called over and over
                        @Suppress("NAME_SHADOWING") val lastMessageServerID = apiDatabase.getLastMessageServerID(channel, server)
                        if (serverID > lastMessageServerID ?: 0) { apiDatabase.setLastMessageServerID(channel, server, serverID) }
                        val hexEncodedSignature = value.get("sig").asText()
                        val signatureVersion = value.get("sigver").asLong()
                        val signature = LokiPublicChatMessage.Signature(Hex.fromStringCondensed(hexEncodedSignature), signatureVersion)
                        // Verify the message
                        val groupMessage = LokiPublicChatMessage(serverID, hexEncodedPublicKey, displayName, body, timestamp, publicChatMessageType, quote, attachments, signature)
                        if (groupMessage.hasValidSignature()) groupMessage else null
                    } catch (exception: Exception) {
                        Log.d("Loki", "Couldn't parse message for public chat channel with ID: $channel on server: $server from: ${JsonUtil.toJson(message)}. Exception: ${exception.message}")
                        return@mapNotNull null
                    }
                }.sortedBy { it.timestamp }
                messages
            } catch (exception: Exception) {
                Log.d("Loki", "Couldn't parse messages for public chat channel with ID: $channel on server: $server.")
                throw exception
            }
        }
    }

    public fun getDeletedMessageServerIDs(channel: Long, server: String): Promise<List<Long>, Exception> {
        Log.d("Loki", "Getting deleted messages for public chat channel with ID: $channel on server: $server.")
        val parameters = mutableMapOf<String, Any>()
        val lastDeletionServerID = apiDatabase.getLastDeletionServerID(channel, server)
        if (lastDeletionServerID != null) {
            parameters["since_id"] = lastDeletionServerID
        } else {
            parameters["count"] = fallbackBatchCount
        }
        return execute(HTTPVerb.GET, server, "loki/v1/channel/$channel/deletes", parameters).then { response ->
            try {
                val bodyAsString = response.body()!!.string()
                val body = JsonUtil.fromJson(bodyAsString)
                val deletedMessageServerIDs = body.get("data").mapNotNull { deletion ->
                    try {
                        val serverID = deletion.get("id").asLong()
                        val messageServerID = deletion.get("message_id").asLong()
                        @Suppress("NAME_SHADOWING") val lastDeletionServerID = apiDatabase.getLastDeletionServerID(channel, server)
                        if (serverID > (lastDeletionServerID ?: 0)) { apiDatabase.setLastDeletionServerID(channel, server, serverID) }
                        messageServerID
                    } catch (exception: Exception) {
                        Log.d("Loki", "Couldn't parse deleted message for public chat channel with ID: $channel on server: $server. ${exception.message}")
                        return@mapNotNull null
                    }
                }
                deletedMessageServerIDs
            } catch (exception: Exception) {
                Log.d("Loki", "Couldn't parse deleted messages for public chat channel with ID: $channel on server: $server.")
                throw exception
            }
        }
    }

    public fun sendMessage(message: LokiPublicChatMessage, channel: Long, server: String): Promise<LokiPublicChatMessage, Exception> {
        val signedMessage = message.sign(userPrivateKey) ?: return Promise.ofFail(LokiAPI.Error.MessageSigningFailed)
        return retryIfNeeded(maxRetryCount) {
            Log.d("Loki", "Sending message to public chat channel with ID: $channel on server: $server.")
            val parameters = signedMessage.toJSON()
            execute(HTTPVerb.POST, server, "channels/$channel/messages", parameters = parameters).then { response ->
                try {
                    val bodyAsString = response.body()!!.string()
                    val body = JsonUtil.fromJson(bodyAsString)
                    val data = body.get("data")
                    val serverID = data.get("id").asLong()
                    val displayName = userDatabase.getDisplayName(userHexEncodedPublicKey) ?: "Anonymous"
                    val text = data.get("text").asText()
                    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    val dateAsString = data.get("created_at").asText()
                    val timestamp = format.parse(dateAsString).time
                    @Suppress("NAME_SHADOWING") val message = LokiPublicChatMessage(serverID, userHexEncodedPublicKey, displayName, text, timestamp, publicChatMessageType, message.quote, message.attachments, signedMessage.signature)
                    message
                } catch (exception: Exception) {
                    Log.d("Loki", "Couldn't parse message for public chat channel with ID: $channel on server: $server.")
                    throw exception
                }
            }.get()
        }.success {
            Analytics.shared.track("Group Message Sent") // Should ideally be Public Chat Message Sent
        }.fail {
            Analytics.shared.track("Failed to Send Group Message") // Should ideally be Failed to Send Public Chat Message
        }
    }

    public fun deleteMessage(messageServerID: Long, channel: Long, server: String, isSentByUser: Boolean): Promise<Long, Exception> {
        return retryIfNeeded(maxRetryCount) {
            val isModerationRequest = !isSentByUser
            Log.d("Loki", "Deleting message with ID: $messageServerID from public chat channel with ID: $channel on server: $server (isModerationRequest = $isModerationRequest).")
            val endpoint = if (isSentByUser) "channels/$channel/messages/$messageServerID" else "loki/v1/moderation/message/$messageServerID"
            execute(HTTPVerb.DELETE, server, endpoint).then {
                Log.d("Loki", "Deleted message with ID: $messageServerID from public chat channel with ID: $channel on server: $server.")
                messageServerID
            }.get()
        }
    }

    public fun getModerators(channel: Long, server: String): Promise<Set<String>, Exception> {
        return execute(HTTPVerb.GET, server, "loki/v1/channel/$channel/get_moderators").then { response ->
            try {
                val bodyAsString = response.body()!!.string()
                @Suppress("NAME_SHADOWING") val body = JsonUtil.fromJson(bodyAsString, Map::class.java)
                @Suppress("UNCHECKED_CAST") val moderators = body["moderators"] as? List<String>
                val moderatorsAsSet = moderators.orEmpty().toSet()
                if (Companion.moderators[server] != null) {
                    Companion.moderators[server]!![channel] = moderatorsAsSet
                } else {
                    Companion.moderators[server] = hashMapOf( channel to moderatorsAsSet )
                }
                moderatorsAsSet
            } catch (exception: Exception) {
                Log.d("Loki", "Couldn't parse moderators for public chat channel with ID: $channel on server: $server.")
                throw exception
            }
        }
    }

    public fun getChannelInfo(channel: Long, server: String): Promise<String, Exception> {
        val parameters = mapOf( "include_annotations" to 1 )
        return execute(HTTPVerb.GET, server, "/channels/$channel", parameters).then { response ->
            try {
                val bodyAsString = response.body()!!.string()
                val body = JsonUtil.fromJson(bodyAsString)
                val data = body.get("data")
                val annotations = data.get("annotations")
                val annotation = annotations.find { it.get("type").asText("") == channelInfoType } ?: throw Error.ParsingFailed
                val info = annotation.get("value")
                info.get("name").asText()
            } catch (exception: Exception) {
                Log.d("Loki", "Couldn't parse info for public chat channel with ID: $channel on server: $server.")
                throw exception
            }
        }
    }

    public fun setDisplayName(newDisplayName: String?, server: String): Promise<Unit, Exception> {
        Log.d("Loki", "Updating display name on server: $server.")
        val parameters = mapOf( "name" to (newDisplayName ?: "") )
        return execute(HTTPVerb.PATCH, server, "users/me", parameters = parameters).map { Unit }
    }
    // endregion
}